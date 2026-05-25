package pt.paradigmshift.babel.lora;

import lora.LoRaHAT;
import lora.LoRaPacket;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pt.paradigmshift.babel.lora.notifications.LoRaPacketReceivedNotification;
import pt.paradigmshift.babel.radio.RadioAddress;
import pt.paradigmshift.babel.radio.notifications.RadioSendFailedNotification;
import pt.paradigmshift.babel.radio.requests.BroadcastRadioPacketRequest;
import pt.paradigmshift.babel.radio.requests.SendRadioPacketRequest;
import pt.unl.fct.di.novasys.babel.core.GenericProtocol;
import pt.unl.fct.di.novasys.babel.exceptions.HandlerRegistrationException;

import java.util.Properties;

/**
 * Babel protocol that adapts a {@link LoRaHAT} driver to the shared
 * {@code babel-radio-api} request/notification surface. Multiple Babel
 * protocols on the same gateway can share a single LoRa radio by tagging
 * their outbound traffic with their own {@code sourceProto} (their
 * {@code PROTOCOL_ID}); the protocol writes those two bytes to the wire
 * and surfaces them again on inbound notifications.
 *
 * <h2>Wire layout inside the LoRa payload</h2>
 * <pre>
 *   [ 2 bytes sourceProto (big-endian) ][ user payload ... ]
 * </pre>
 *
 * <h2>Inbound notifications</h2>
 * The protocol emits {@link LoRaPacketReceivedNotification}, a subclass of
 * {@link pt.paradigmshift.babel.radio.notifications.RadioPacketReceivedNotification}
 * that carries the LoRa-specific extras (previous hop, destination, channel,
 * RSSI). Generic subscribers see them as the base type; LoRa-aware
 * subscribers cast to the subclass to access the extras.
 *
 * <h2>Lifecycle</h2>
 * The application owns the Pi4J {@link com.pi4j.context.Context} and the
 * {@link LoRaHAT} (the gateway must share one Pi4J context across all
 * Pi-touching protocols). Build and initialise the HAT first, then
 * construct the protocol with that HAT. {@link #init(Properties)} only
 * wires the inbound callback — the radio is already running by then.
 *
 * <h2>Identifiers</h2>
 * <p><b>Protocol ID:</b> {@value #PROTOCOL_ID}.
 * <p>This protocol uses the shared {@code babel-radio-api} event surface — its
 * inbound packet notification ({@link LoRaPacketReceivedNotification},
 * subclass of {@link pt.paradigmshift.babel.radio.notifications.RadioPacketReceivedNotification})
 * inherits {@code NOTIFICATION_ID = 401} from the radio-api's reserved slot 400.
 * The protocol declares no own event IDs.
 */
public class LoRaProtocol extends GenericProtocol {

    private static final Logger logger =
            LogManager.getLogger(LoRaProtocol.class);

    public static final String PROTOCOL_NAME = "LoRa";
    public static final short PROTOCOL_ID = 1100;

    /**
     * Maximum user payload (in bytes) accepted by send/broadcast requests.
     * Derived from the default E22 buffer size (240 B), minus the 8-byte
     * {@link LoRaPacket} header and the 2-byte sourceProto envelope this
     * protocol adds to every frame.
     */
    public static final int MAX_USER_PAYLOAD_BYTES = 230;

    private static final int BROADCAST_ADDR = 0xFFFF;
    private static final int SOURCE_PROTO_BYTES = 2;

    private final LoRaHAT hat;
    private final LoRaAddress ownAddress;

    /**
     * @param hat        a fully constructed and initialised {@link LoRaHAT}
     * @param ownAddress the 16-bit LoRa address this gateway uses as the
     *                   {@code origin} on outbound packets
     */
    public LoRaProtocol(LoRaHAT hat, int ownAddress)
            throws HandlerRegistrationException {
        super(PROTOCOL_NAME, PROTOCOL_ID);
        this.hat = hat;
        this.ownAddress = new LoRaAddress(ownAddress);

        registerRequestHandler(SendRadioPacketRequest.REQUEST_ID,
                               this::uponSendRequest);
        registerRequestHandler(BroadcastRadioPacketRequest.REQUEST_ID,
                               this::uponBroadcastRequest);
    }

    @Override
    public void init(Properties props) {
        // The LoRaHAT reader thread is already running; wire it into Babel.
        // triggerNotification is safe from foreign threads (Babel posts to
        // each subscriber's LinkedBlockingQueue).
        hat.setPacketHandler(this::deliverIncoming);
    }

    private void uponSendRequest(SendRadioPacketRequest req,
                                 short ignored) {
        RadioAddress dst = req.getDestination();
        if (!(dst instanceof LoRaAddress lora)) {
            triggerNotification(new RadioSendFailedNotification(
                    req.getSourceProto(), dst,
                    "LoRaProtocol received non-LoRaAddress destination: "
                            + (dst == null ? "null" : dst.getClass().getName())));
            return;
        }
        transmit(lora, req.getSourceProto(), req.getPayload());
    }

    private void uponBroadcastRequest(BroadcastRadioPacketRequest req,
                                      short ignored) {
        transmit(new LoRaAddress(BROADCAST_ADDR), req.getSourceProto(),
                 req.getPayload());
    }

    private void transmit(LoRaAddress destination, short sourceProto,
                          byte[] payload) {
        if (payload.length > MAX_USER_PAYLOAD_BYTES) {
            triggerNotification(new RadioSendFailedNotification(
                    sourceProto, destination,
                    "Payload " + payload.length + "B exceeds MTU "
                            + MAX_USER_PAYLOAD_BYTES + "B"));
            return;
        }

        byte[] enveloped = new byte[SOURCE_PROTO_BYTES + payload.length];
        enveloped[0] = (byte) ((sourceProto >> 8) & 0xFF);
        enveloped[1] = (byte) (sourceProto & 0xFF);
        System.arraycopy(payload, 0, enveloped, SOURCE_PROTO_BYTES,
                         payload.length);

        try {
            LoRaPacket packet = new LoRaPacket.Builder()
                    .origin(ownAddress.getAddress())
                    .previousHop(ownAddress.getAddress())
                    .destination(destination.getAddress())
                    .payload(enveloped)
                    .build();
            hat.transmit(packet);
        } catch (Exception e) {
            logger.warn("LoRa transmit failed for sourceProto={} dest={}: {}",
                        sourceProto, destination, e.toString());
            triggerNotification(new RadioSendFailedNotification(
                    sourceProto, destination, e.toString()));
        }
    }

    private void deliverIncoming(LoRaPacket packet) {
        byte[] enveloped = packet.getPayload();
        if (enveloped == null || enveloped.length < SOURCE_PROTO_BYTES) {
            // Foreign sender that doesn't speak our envelope — silently drop.
            return;
        }
        short sourceProto = (short) (((enveloped[0] & 0xFF) << 8)
                                     | (enveloped[1] & 0xFF));
        byte[] payload = new byte[enveloped.length - SOURCE_PROTO_BYTES];
        System.arraycopy(enveloped, SOURCE_PROTO_BYTES, payload, 0,
                         payload.length);

        triggerNotification(new LoRaPacketReceivedNotification(
                sourceProto,
                new LoRaAddress(packet.getOriginAddr()),
                new LoRaAddress(packet.getPrevHopAddr()),
                new LoRaAddress(packet.getDestAddr()),
                packet.getChannel(),
                packet.getRssi(),
                payload));
    }
}
