# Babel LoRa Protocol

A [Babel](https://github.com/pfouto/babel) `GenericProtocol` that exposes the
ParadigmShift [LoRa HAT driver](../babel-lora-standalone) as Babel
requests/notifications, so that one or many Babel protocols on a Raspberry Pi
gateway can share a single Waveshare SX126X (EByte E22-900T22S) radio.

**Group ID:** `pt.paradigmshift.babel`
**Artifact ID:** `babel-lora-protocol`
**Current version:** `0.3.0`
**Tested with:** `pt.paradigmshift.iot:babel-lora:0.2.2` driver,
`pt.paradigmshift.babel:babel-radio-api:0.2.0`, and
`pt.paradigmshift.babel:babel-core:1.0.0`.

> **0.3.0 is a breaking release.** It bumps the transitive
> `babel-radio-api` dependency to `0.2.0`, which renumbered the shared
> radio events into the reserved slot `400` (was `100`/`101`). Subscribers
> recompile against the new IDs; no API names change. See the *Identifiers*
> section below and the `babel-radio-api` 0.2.0 release notes.

> **0.2.0** was the earlier breaking release that moved the request and
> notification types to the shared `babel-radio-api` library and changed
> the destination type from `int` to `LoRaAddress`. See *Migration*.

---

## Multi-protocol sharing

LoRa is a single half-duplex broadcast medium. To let multiple protocols share
it without stepping on each other, every send/broadcast request is tagged with
the sender's 16-bit `sourceProto` (its Babel `PROTOCOL_ID`). On the wire, this
protocol prepends two bytes inside the `LoRaPacket` payload:

```
[ 2 bytes sourceProto (big-endian) ][ user payload ... ]
```

Inbound packets are delivered to every protocol that subscribed to
`RadioPacketReceivedNotification`; each one filters by its own `PROTOCOL_ID`.
The LoRa protocol emits a subclass — `LoRaPacketReceivedNotification` —
carrying the LoRa-specific extras (previous hop, destination, channel,
RSSI). Generic subscribers see only the base type; LoRa-aware subscribers
cast to the subclass:

```java
subscribeNotification(RadioPacketReceivedNotification.NOTIFICATION_ID, (n, src) -> {
    if (n.getSourceProto() != MY_PROTOCOL_ID) return;
    if (n instanceof LoRaPacketReceivedNotification lo) {
        handlePeerMessage(lo.getLoRaOrigin(), lo.getPayload(), lo.getRssi());
    }
});
```

`sourceProto` here is the *remote* sender's protocol id, carried in the wire
envelope — distinct from the local `sourceProto` parameter Babel passes to
every handler (which is always `LoRaProtocol.PROTOCOL_ID` for these
notifications). The naming mirrors `BabelMessage.getSourceProto()`, which
plays the same role for in-process messages.

This is the standard Babel pub/sub model — no new dispatch table on top, no
shared mutable state, no ordering surprises. Anyone wanting to use the radio
just `sendRequest(...)` with their own `PROTOCOL_ID` as `sourceProto`.

---

## Protocol & event identifiers

Follows the ParadigmShift workspace Babel ID convention: protocols at
100-multiples, events numbered `protocol_id + N` per handler class. This
protocol owns slot `1100`; its only own notification class is
`LoRaPacketReceivedNotification`, which inherits its ID from the shared
`babel-radio-api` reserved slot `400` instead of being numbered under
`1100` — every radio in the workspace points to the same shared event so
multi-radio subscribers can register one handler.

| Type | Origin | Handler class | ID | Purpose |
|---|---|---|---|---|
| `LoRaProtocol`                    | `babel-lora-protocol`    | protocol      | `1100` | This protocol's `PROTOCOL_ID` |
| `SendRadioPacketRequest`          | `babel-radio-api`        | request/reply | `401`  | Unicast a payload — `destination` is a `LoRaAddress` |
| `BroadcastRadioPacketRequest`     | `babel-radio-api`        | request/reply | `402`  | Broadcast a payload (LoRa air-address `0xFFFF`) |
| `RadioPacketReceivedNotification` | `babel-radio-api`        | notification  | `401`  | Generic inbound packet — emitted as `LoRaPacketReceivedNotification` (subclass) carrying RSSI / prevHop / destination / channel |
| `RadioSendFailedNotification`     | `babel-radio-api`        | notification  | `402`  | MTU exceeded, wrong-radio destination, or driver throw |
| `LoRaAddress`                     | `babel-lora-protocol`    | —             | —      | 16-bit LoRa on-air address; `RadioAddress` subclass (carries no event id) |

Routing from generic application code is one call:
`addr.owningProtocolId()` returns `1100` for any `LoRaAddress`.

`MAX_USER_PAYLOAD_BYTES = 230` (= 240 B E22 buffer − 8 B `LoRaPacket` header
− 2 B `sourceProto` envelope). Requests with a larger payload trigger
`RadioSendFailedNotification`.

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
        <version>0.3.0</version>
    </dependency>
</dependencies>
```

This artifact pulls in `pt.paradigmshift.iot:babel-lora` (the driver),
`pt.paradigmshift.babel:babel-radio-api` (the shared request/notification
types), and `pt.paradigmshift.babel:babel-core` transitively.

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
        subscribeNotification(RadioPacketReceivedNotification.NOTIFICATION_ID, this::onRadioIn);
        subscribeNotification(RadioSendFailedNotification.NOTIFICATION_ID,     this::onRadioFail);
    }

    private void gossip(byte[] payload) {
        sendRequest(new BroadcastRadioPacketRequest(PROTOCOL_ID, payload),
                    LoRaProtocol.PROTOCOL_ID);
    }

    private void unicast(LoRaAddress peer, byte[] payload) {
        // Radio-agnostic routing: the address knows which protocol owns it.
        sendRequest(new SendRadioPacketRequest(PROTOCOL_ID, peer, payload),
                    peer.owningProtocolId());
    }

    private void onRadioIn(RadioPacketReceivedNotification n, short src) {
        if (n.getSourceProto() != PROTOCOL_ID) return;             // not for us
        if (src != LoRaProtocol.PROTOCOL_ID) return;               // not LoRa
        LoRaPacketReceivedNotification lo = (LoRaPacketReceivedNotification) n;
        handleGossip(lo.getLoRaOrigin(), lo.getPayload(), lo.getRssi());
    }

    private void onRadioFail(RadioSendFailedNotification n, short src) {
        if (n.getSourceProto() != PROTOCOL_ID) return;
        logger.warn("Radio send failed to {}: {}",
                    n.getDestination(), n.getReason());
    }
}
```

Two unrelated protocols can coexist with no further coordination — they stamp
their own `PROTOCOL_ID` as `sourceProto` on every send, filter on
`n.getSourceProto()` in their handlers, and ignore the rest.

### Migration from 0.1.x

| 0.1.x type | 0.2.0 replacement |
|---|---|
| `SendLoRaPacketRequest(sp, int dest, payload)` | `SendRadioPacketRequest(sp, new LoRaAddress(dest), payload)` |
| `BroadcastLoRaRequest(sp, payload)` | `BroadcastRadioPacketRequest(sp, payload)` |
| `LoRaPacketReceivedNotification` | still exists, but now `extends RadioPacketReceivedNotification`; subscribe to `RadioPacketReceivedNotification.NOTIFICATION_ID` and `instanceof`-cast to access RSSI / prevHop / etc. Inbound-address accessors return `LoRaAddress` (no longer `int`). |
| `LoRaSendFailedNotification` | `RadioSendFailedNotification`; destination is a `RadioAddress` (cast to `LoRaAddress` if you need the numeric value) |

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
git tag v0.3.0
git push origin v0.3.0
```

---

## License

Copyright (c) 2026 ParadigmShift, Lda. See [LICENSE](LICENSE) for full terms.

Commercial use outside of ParadigmShift requires a written licence.
Contact: [info@paradigmshift.pt](mailto:info@paradigmshift.pt)
