# Decisiones

Notas de diseño del servicio de reservas: supuestos que tomé, lo que dejé fuera a propósito y lo
que cambiaría antes de llevarlo a producción.

## Cómo está organizado

```
com.store.inventory
├── Inventory                    punto de entrada (arma las piezas)
├── api/                         contrato, sin cambios
├── reservation/
│   ├── InMemoryInventoryService orquesta reservas, confirmaciones y expiración
│   └── Product                  estado de un SKU (stock, reservas pendientes, bandera de alerta)
├── category/
│   ├── CategoryPolicies         tabla de reglas por categoría + guardia
│   └── CategoryPolicy           una fila de esa tabla (ventana de pago, límite por pedido)
└── alerts/
    ├── LowStockAlerts           umbral de stock bajo y entrega del aviso
    └── CompositeStockAlertListener  un aviso → varios canales
```

La regla que más cambia (lo que dicta cada categoría) vive en un solo lugar, `CategoryPolicies.defaults()`.
Cuando Marketing cree una categoría nueva hay que agregar el valor al enum y una fila a esa tabla; si
alguien olvida la fila, el servicio se niega a arrancar y dice qué categoría falta. El umbral de alerta
(5 unidades) es un parámetro de `LowStockAlerts`. Los paquetes siguen los conceptos del negocio
(reserva, categoría, aviso) para que quien cambie una regla sepa dónde buscar.

## Supuestos

| Tema | Supuesto |
|---|---|
| Reenvíos de la app | Un `orderId` repetido con el mismo SKU y cantidad **es un reintento**: devuelve la reserva original y no aparta nada más. El mismo `orderId` con otro contenido es un error del cliente (`IllegalArgumentException`). |
| Reintento tras expirar | Si la reserva ya venció y la app reintenta, se crea una reserva **nueva** (si hay unidades). El cliente sigue queriendo comprar. |
| Reintento tras confirmar | Devuelve la reserva original. La app recibe respuesta y no se duplica la venta. |
| Frontera de expiración | La reserva está activa mientras `now < expiresAt`. En el instante exacto de `expiresAt` ya se liberó. |
| `confirm` | Literal al contrato: sólo confirma una reserva activa. Un pedido desconocido, vencido o **ya confirmado** lanza `IllegalStateException`. |
| Orden de validación en `reserve` | cantidad > 0 → límite de la categoría → existencias. El límite por pedido es una regla del pedido, no del almacén; se reporta aunque no haya stock. Un SKU desconocido tiene 0 disponibles (`InsufficientStockException`). |
| `registerProduct` repetido | Con la misma categoría es inofensivo. Cambiar la categoría de un producto ya registrado se rechaza: cambiaría las reglas de reservas vivas. |
| Disponibilidad | `stock − reservas activas`. Confirmar no la mueve (la unidad ya estaba apartada); sólo pasa de apartada a vendida. |
| Alerta de stock bajo | Se evalúa **al reservar** (lo único que baja la disponibilidad). Se dispara una vez cuando quedan ≤ 5 y se **rearma al reabastecer** (`addStock`). Que venzan reservas y suban las unidades no rearma la alerta: no hubo reabastecimiento. |
| Canal de aviso caído | Si el listener lanza, la reserva **ya quedó hecha** y no se revierte; se registra en el log y la alerta se vuelve a intentar en el siguiente cambio de ese producto. |
| Varios canales | `StockAlertListener` ya es el puerto. Correo hoy, otros canales mañana = `CompositeStockAlertListener.of(correo, chat, ...)` al armar el servicio. |

## Decisiones de diseño

**Librería en proceso, no un servicio desplegable.** El contrato manda: la app y los tests automáticos
llaman `Inventory.create(Clock, StockAlertListener)` y hablan con `InventoryService` directamente. Exponerlo
por HTTP habría sumado un framework que nadie pidió, no lo habría ejercitado ninguna prueba del contrato y
habría dejado la expiración fuera del alcance del `Clock` inyectado. Desplegarlo como proceso propio es el
paso siguiente, cuando llegue la base de datos y las varias instancias (ver abajo).

**Expiración perezosa, sin hilos.** No hay scheduler que libere reservas. Cada reserva lleva su `expiresAt`
y al tocar un producto (reservar, consultar, confirmar) se purgan las vencidas. Ventajas: determinista
y testeable con `Clock`, sin estado oculto, y es exactamente el modelo que sobrevive al pasar a base de
datos y a varias instancias (la fila dice cuándo vence; nadie tiene que "recordar" liberarla).

**Un solo lock para todo el estado en memoria.** Las operaciones duran microsegundos y la etapa en memoria
es temporal. Un lock por SKU daría más concurrencia pero complica la idempotencia por `orderId`
(índice global) sin que haya evidencia de que haga falta. `ConcurrentReservationsTest` demuestra que
400 clientes contra 50 unidades no sobrevenden ni duplican la alerta, y que 50 reintentos paralelos del
mismo pedido reservan una sola vez.

**La alerta se reclama dentro del lock y se entrega fuera.** Así dos reservas concurrentes no avisan dos
veces y el envío de correo (lento) no bloquea las ventas. El estado (bandera por producto) y el lock son
del servicio; `LowStockAlerts` sólo sabe el umbral y cómo entregar.

**Sin dependencias nuevas.** JUnit era suficiente. El log usa `System.Logger` del JDK para que el equipo
enchufe el backend que ya tenga.

## Lo que dejé fuera

- **Liberación/cancelación explícita** de una reserva (el cliente vacía el carrito). El contrato no la
  pide; sería `release(orderId)` y es trivial con este modelo.
- **Decremento o ajuste de stock** por mermas o inventario físico. Sólo hay `addStock`.
- **Historial** de reservas confirmadas o vencidas. Las vencidas se olvidan al purgar; las confirmadas se
  conservan sólo para responder reintentos.
- **Métricas** (reservas por minuto, tasa de expiración, tiempo hasta el pago). Son el primer insumo
  para afinar las ventanas por categoría.

## Antes de llevarlo a producción

1. **Persistencia.** Tabla `reservations (order_id PK, sku, quantity, expires_at, status)` y `products
   (sku PK, category, stock, low_stock_alerted)`. `available` pasa a ser
   `stock − SUM(quantity) WHERE status = 'PENDING' AND expires_at > now()`.
2. **Concurrencia entre instancias.** El lock en memoria no sirve con varias réplicas. Reservar debe ser
   una transacción con `SELECT … FOR UPDATE` sobre la fila del producto (o versión optimista con
   reintento), y la idempotencia por `order_id` la da la PK: el segundo insert choca y se devuelve la fila
   existente.
3. **Reloj.** Usar el `now()` de la base de datos, no el de cada instancia, para que la expiración sea
   la misma en todas.
4. **Limpieza.** Un job periódico que archive reservas vencidas y confirmadas antiguas; con la purga
   perezosa basta en memoria, no en una tabla que crece sin tope.
5. **Alertas confiables.** Hoy el aviso se pierde si el canal falla justo antes del siguiente cambio del
   producto. Con base de datos conviene un *outbox*: la misma transacción que reserva escribe el aviso
   pendiente y un worker lo entrega con reintentos.
6. **`confirm` idempotente por pago.** El contrato lanza al confirmar dos veces; los webhooks de los
   proveedores de pago llegan repetidos. Guardar el `paymentId` y tratar el duplicado como no-op.
7. **Reglas de categoría como datos.** Si Marketing crea categorías cada temporada, la tabla de políticas
   debería vivir en configuración o base de datos, con el enum convertido en catálogo. Hoy requiere un
   despliegue; la guardia de `CategoryPolicies` ya evita el error más común (categoría sin reglas).
8. **Exponerlo como servicio propio.** Una fachada HTTP delgada sobre `InventoryService` (reservar, confirmar,
   consultar disponibilidad), con el `orderId` como clave de idempotencia en la petición. La lógica no cambia:
   el contrato ya es la frontera.
9. **Observabilidad.** Métricas y trazas por operación, y una alerta operativa cuando la tasa de
   `InsufficientStockException` se dispare (señal de demanda que compras quiere ver).

## Cómo lo probé

```bash
mvn test
```

50 pruebas: las 3 originales intactas más ciclo de vida de la reserva (expiración, confirmación,
reintentos, validaciones), reglas por categoría (parametrizadas), alertas de stock bajo, concurrencia
(repetida 5 veces) y la guardia de la tabla de políticas. Además verifiqué a mano que tres mutaciones
rompen la suite: correr la frontera de expiración un segundo, quitar la deduplicación de alertas y
quitar la exclusión mutua.
