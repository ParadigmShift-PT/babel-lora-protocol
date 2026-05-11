# Babel LoRa Protocol

A [Babel](https://github.com/pfouto/babel) `GenericProtocol` that exposes the
ParadigmShift [LoRa HAT driver](../babel-lora-standalone) as Babel
requests/notifications, so that one or many Babel protocols on a Raspberry Pi
gateway can share a single Waveshare SX126X (EByte E22-900T22S) radio.

**Group ID:** `pt.paradigmshift.babel`
**Artifact ID:** `babel-lora-protocol`
**Current version:** `0.1.0`
**Tested with:** `pt.paradigmshift.iot:babel-lora:0.2.0` driver and
`pt.paradigmshift.babel:babel-core:1.0.0`.

---

## Multi-protocol sharing

LoRa is a single half-duplex broadcast medium. To let multiple protocols share
it without stepping on each other, every send/broadcast request is tagged with
a 16-bit `appId` (by convention the sender's Babel protocol id). On the wire,
this protocol prepends two bytes inside the `LoRaPacket` payload:

```
[ 2 bytes app_id (big-endian) ][ user payload ... ]
```

Inbound packets are delivered to every protocol that subscribed to
`LoRaPacketReceivedNotification`; each one filters by its own `appId`:

```java
subscribeNotification(LoRaPacketReceivedNotification.NOTIFICATION_ID, (n, src) -> {
    if (n.getAppId() != MY_PROTO_ID) return;
    handlePeerMessage(n.getOriginAddress(), n.getPayload(), n.getRssi());
});
```

This is the standard Babel pub/sub model — no new dispatch table on top, no
shared mutable state, no ordering surprises. Anyone wanting to use the radio
just `sendRequest(...)` with their own `appId`.

---

## Request / notification surface

| Type | ID | Purpose |
|---|---|---|
| `SendLoRaPacketRequest`         | `1100` (request)      | Unicast a payload to a specific 16-bit LoRa address |
| `BroadcastLoRaRequest`          | `1101` (request)      | Broadcast a payload (dest = `0xFFFF`) |
| `LoRaPacketReceivedNotification`| `1100` (notification) | One per received packet — fan-out to every subscriber |
| `LoRaSendFailedNotification`    | `1101` (notification) | MTU exceeded, or the driver threw on transmit |

The protocol itself registers as id `1100`.

`MAX_USER_PAYLOAD_BYTES = 230` (= 240 B E22 buffer − 8 B `LoRaPacket` header − 2 B
`appId` envelope). Requests with a larger payload trigger `LoRaSendFailedNotification`.

---

## Usage

Add to your `pom.xml`:

```xml
<repositories>
    <repository>
        <id>paradigmshift-repository</id>
        <name>ParadigmShift Repository</name>
        <url>https://maven.paradigmshift.pt/releases</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>pt.paradigmshift.babel</groupId>
        <artifactId>babel-lora-protocol</artifactId>
        <version>0.1.0</version>
    </dependency>
</dependencies>
```

This artifact pulls in `pt.paradigmshift.iot:babel-lora` (the driver) and
`pt.paradigmshift.babel:babel-core` transitively.

### Wiring it up in `Main`

The application owns the single Pi4J `Context` and the `LoRaHAT` (the gateway
must share one Pi4J context across every Pi-touching protocol):

```java
Context pi4j = Pi4J.newContextBuilder()
        .noAutoDetect()
        .add(new RaspberryPiPlatform() {
            @Override protected String[] getProviders() { return new String[]{}; }
        })
        .add(GpioDDigitalInputProvider.newInstance(),
             GpioDDigitalOutputProvider.newInstance(),
             LinuxFsI2CProvider.newInstance(),
             RpiSpiProvider.newInstance())
        .build();

LoRaHAT hat = new LoRaHAT(pi4j, /* ownAddress */ 0x4321, "/dev/ttyAMA0");
hat.init();

Babel babel = Babel.getInstance();
LoRaProtocol lora = new LoRaProtocol(hat, 0x4321);
babel.registerProtocol(lora);
lora.init(props);
babel.start();
```

### Sending from another protocol

```java
public class MyMeshProtocol extends GenericProtocol {
    public static final short PROTOCOL_ID = 1200;

    public MyMeshProtocol() throws HandlerRegistrationException {
        super("MyMesh", PROTOCOL_ID);
        subscribeNotification(LoRaPacketReceivedNotification.NOTIFICATION_ID, this::onLoRaIn);
        subscribeNotification(LoRaSendFailedNotification.NOTIFICATION_ID,    this::onLoRaFail);
    }

    private void gossip(byte[] payload) {
        sendRequest(new BroadcastLoRaRequest(PROTOCOL_ID, payload), LoRaProtocol.PROTOCOL_ID);
    }

    private void onLoRaIn(LoRaPacketReceivedNotification n, short src) {
        if (n.getAppId() != PROTOCOL_ID) return;   // not for us
        handleGossip(n.getOriginAddress(), n.getPayload(), n.getRssi());
    }

    private void onLoRaFail(LoRaSendFailedNotification n, short src) {
        if (n.getAppId() != PROTOCOL_ID) return;
        logger.warn("LoRa send failed to 0x{}: {}",
                    String.format("%04X", n.getDestAddress()), n.getReason());
    }
}
```

Two unrelated protocols can coexist with no further coordination — they pick
distinct `appId` values (their own `PROTOCOL_ID` is the obvious choice), filter
in their handlers, and ignore the rest.

---

## Threading note

`LoRaHAT` invokes the registered packet handler from its own reader thread.
This protocol installs `this::deliverIncoming` as that handler, which calls
`triggerNotification(...)` — safe from any thread, since `Babel` delivers each
notification through every subscriber's `LinkedBlockingQueue`. Subscribers
therefore observe inbound packets on their normal protocol event loop.

---

## Building

Requires Java 17 and Maven 3.6+.

```bash
mvn verify    # compile + (no) tests
mvn package   # produces JAR, sources JAR, and Javadoc JAR
mvn install   # install to ~/.m2/
mvn deploy    # publish to maven.paradigmshift.pt (requires REPOSILITE_TOKEN)
```

This library compiles anywhere; running it requires a Raspberry Pi with the
SX126X HAT (because of the transitive driver/Pi4J native dependencies).

## Releasing

Push a version tag — CI deploys automatically (mirroring the other
ParadigmShift Maven libs):

```bash
git tag v0.1.0
git push origin v0.1.0
```

---

## License

Copyright (c) 2026 ParadigmShift, Lda. See [LICENSE](LICENSE) for full terms.

Commercial use outside of ParadigmShift requires a written licence.
Contact: [info@paradigmshift.pt](mailto:info@paradigmshift.pt)
