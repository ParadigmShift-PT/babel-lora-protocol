package pt.paradigmshift.babel.lora;

import lora.LoRaHAT;
import lora.LoRaPacket;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pt.paradigmshift.babel.lora.notifications.LoRaPacketReceivedNotification;
import pt.paradigmshift.babel.radio.RadioAddress;
import pt.paradigmshift.babel.radio.frag.RadioFragmenter;
import pt.paradigmshift.babel.radio.frag.RadioReassembler;
import pt.paradigmshift.babel.radio.notifications.RadioSendFailedNotification;
import pt.paradigmshift.babel.radio.requests.BroadcastRadioPacketRequest;
import pt.paradigmshift.babel.radio.requests.SendRadioPacketRequest;
import pt.unl.fct.di.novasys.babel.core.GenericProtocol;
import pt.unl.fct.di.novasys.babel.exceptions.HandlerRegistrationException;

import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * Babel protocol that adapts a {@link LoRaHAT} driver to the shared
 * {@code babel-radio-api} request/notification surface. Multiple Babel
 * protocols on the same gateway can share a single LoRa radio by tagging
 * each frame with a 2-byte {@code destProto} — the id of the protocol the
 * frame is for (by the symmetric N↔N convention a sender uses its own
 * {@code PROTOCOL_ID}); the protocol writes those two bytes to the wire and
 * surfaces them again on inbound notifications, where subscribers filter on them.
 *
 * <h2>Wire layout inside the LoRa payload</h2>
 * <pre>
 *   [ 2 bytes destProto (big-endian) ][ user payload ... ]
 * </pre>
 *
 * <h2>Transparent fragmentation</h2>
 * A message larger than one frame is split by {@link RadioFragmenter} into
 * fragment frames (marked by a reserved sentinel in the destProto position)
 * and rebuilt by a {@link RadioReassembler} on receive, so callers send and
 * receive whole payloads of any size up to {@link #MAX_USER_PAYLOAD_BYTES}. A
 * message that fits one frame is sent verbatim — zero overhead, unchanged wire.
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
     * Radio-payload capacity of a single LoRa frame, in bytes — the largest
     * {@code lora_frame_t} payload that survives one on-air frame. Sourced from
     * the driver ({@link LoRaHAT#MAX_FRAME_PAYLOAD_BYTES}) so the FIXED-mode
     * geometry (240-B buffer − 3-B routing prefix − 8-B header = 229) is defined
     * once and stays in lockstep with uBabel's {@code LORA_MAX_PAYLOAD_SIZE}.
     * A message whose enveloped form ([destProto][payload]) exceeds this is
     * transparently fragmented; one that fits is sent verbatim.
     */
    public static final int FRAME_PAYLOAD_CAPACITY = LoRaHAT.MAX_FRAME_PAYLOAD_BYTES;

    /**
     * Maximum user payload (in bytes) a send/broadcast request can carry. With
     * transparent fragmentation this is the fragmented ceiling (up to
     * {@link RadioFragmenter#MAX_FRAGMENTS} frames), not a single-frame limit;
     * a single frame still carries up to {@code FRAME_PAYLOAD_CAPACITY - 2}
     * (227 B) of user payload with zero overhead.
     */
    public static final int MAX_USER_PAYLOAD_BYTES =
            RadioFragmenter.MAX_FRAGMENTS
                    * (FRAME_PAYLOAD_CAPACITY - RadioFragmenter.FRAGMENT_HEADER_BYTES)
                    - 2;

    private static final int BROADCAST_ADDR = LoRaPacket.BROADCAST_ADDR;
    private static final int DEST_PROTO_BYTES = 2;

    private final LoRaHAT hat;
    private final LoRaAddress ownAddress;
    private final RadioReassembler reassembler = new RadioReassembler();
    private int txMsgId = 0;

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
                    req.getDestProto(), dst,
                    "LoRaProtocol received non-LoRaAddress destination: "
                            + (dst == null ? "null" : dst.getClass().getName())));
            return;
        }
        transmit(lora, req.getDestProto(), req.getPayload());
    }

    private void uponBroadcastRequest(BroadcastRadioPacketRequest req,
                                      short ignored) {
        transmit(new LoRaAddress(BROADCAST_ADDR), req.getDestProto(),
                 req.getPayload());
    }

    private void transmit(LoRaAddress destination, short destProto,
                          byte[] payload) {
        byte[] enveloped = new byte[DEST_PROTO_BYTES + payload.length];
        enveloped[0] = (byte) ((destProto >> 8) & 0xFF);
        enveloped[1] = (byte) (destProto & 0xFF);
        System.arraycopy(payload, 0, enveloped, DEST_PROTO_BYTES,
                         payload.length);

        // Transparent fragmentation: a message that fits one frame is sent
        // verbatim (single-element list); a larger one is split. Callers
        // never see fragments.
        List<byte[]> frames;
        try {
            frames = RadioFragmenter.fragment(enveloped, FRAME_PAYLOAD_CAPACITY,
                                              txMsgId++ & 0xFF);
        } catch (IllegalArgumentException e) {
            triggerNotification(new RadioSendFailedNotification(
                    destProto, destination,
                    "Payload " + payload.length + "B too large: "
                            + e.getMessage()));
            return;
        }

        try {
            for (byte[] frame : frames) {
                LoRaPacket packet = new LoRaPacket.Builder()
                        .origin(ownAddress.getAddress())
                        .previousHop(ownAddress.getAddress())
                        .destination(destination.getAddress())
                        .payload(frame)
                        .build();
                hat.transmit(packet);
            }
        } catch (Exception e) {
            logger.warn("LoRa transmit failed for destProto={} dest={}: {}",
                        destProto, destination, e.toString());
            triggerNotification(new RadioSendFailedNotification(
                    destProto, destination, e.toString()));
        }
    }

    private void deliverIncoming(LoRaPacket packet) {
        byte[] framePayload = packet.getPayload();
        if (framePayload == null) {
            return;
        }
        // Transparent reassembly: a non-fragmented frame returns immediately;
        // a fragment is buffered (keyed on the origin address) until its
        // message is complete.
        Optional<byte[]> assembled =
                reassembler.offer(packet.getOriginAddr(), framePayload);
        if (assembled.isEmpty()) {
            return; // incomplete message — waiting for more fragments
        }
        byte[] enveloped = assembled.get();
        if (enveloped.length < DEST_PROTO_BYTES) {
            // Foreign sender that doesn't speak our envelope — silently drop.
            return;
        }
        short destProto = (short) (((enveloped[0] & 0xFF) << 8)
                                   | (enveloped[1] & 0xFF));
        byte[] payload = new byte[enveloped.length - DEST_PROTO_BYTES];
        System.arraycopy(enveloped, DEST_PROTO_BYTES, payload, 0,
                         payload.length);

        triggerNotification(new LoRaPacketReceivedNotification(
                destProto,
                new LoRaAddress(packet.getOriginAddr()),
                new LoRaAddress(packet.getPrevHopAddr()),
                new LoRaAddress(packet.getDestAddr()),
                packet.getChannel(),
                packet.getRssi(),
                payload));
    }
}
