package pt.paradigmshift.babel.lora;

import lora.LoRaHAT;
import lora.LoRaPacket;
import pt.paradigmshift.babel.lora.notifications.LoRaPacketReceivedNotification;
import pt.paradigmshift.babel.lora.notifications.LoRaSendFailedNotification;
import pt.paradigmshift.babel.lora.requests.BroadcastLoRaRequest;
import pt.paradigmshift.babel.lora.requests.SendLoRaPacketRequest;
import pt.unl.fct.di.novasys.babel.core.GenericProtocol;
import pt.unl.fct.di.novasys.babel.exceptions.HandlerRegistrationException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Properties;

/**
 * Babel protocol that adapts a {@link LoRaHAT} driver to the Babel
 * request/notification surface, allowing any number of Babel protocols on the
 * same gateway to share a single LoRa radio.
 *
 * <h2>How sharing works</h2>
 * <p>Each sender stamps its outbound traffic with its own 16-bit
 * {@code sourceProto} (its {@code PROTOCOL_ID}) on
 * {@link SendLoRaPacketRequest} / {@link BroadcastLoRaRequest}; the protocol
 * prepends a two-byte big-endian envelope to the LoRa payload so the receiver
 * side can recover that identifier. On reception, every subscriber of
 * {@link LoRaPacketReceivedNotification} sees every packet — each protocol
 * filters by checking {@code n.getSourceProto() == MY_PROTOCOL_ID}.
 *
 * <h2>Wire format inside the LoRa payload</h2>
 * <pre>
 *   [ 2 bytes sourceProto (big-endian) ][ user payload ... ]
 * </pre>
 *
 * <h2>Lifecycle</h2>
 * <p>The application owns the Pi4J {@link com.pi4j.context.Context} and the
 * {@link LoRaHAT} instance (the gateway must share one Pi4J context across all
 * Pi-touching protocols). Build and initialise the HAT first, then construct
 * the protocol with that HAT. {@link #init(Properties)} only wires the inbound
 * callback — the radio is already running by then.
 */
public class LoRaProtocol extends GenericProtocol {

    private static final Logger logger = LogManager.getLogger(LoRaProtocol.class);

    public static final String PROTOCOL_NAME = "LoRa";
    public static final short PROTOCOL_ID = 1100;

    /**
     * Maximum user payload (in bytes) accepted by send/broadcast requests.
     * Derived from the default E22 buffer size (240 B), minus the 8-byte
     * {@link LoRaPacket} header and the 2-byte sourceProto envelope this
     * protocol adds.
     */
    public static final int MAX_USER_PAYLOAD_BYTES = 230;

    private static final int BROADCAST_ADDR = 0xFFFF;
    private static final int SOURCE_PROTO_ENVELOPE_BYTES = 2;

    private final LoRaHAT hat;
    private final int ownAddress;

    /**
     * @param hat          a fully constructed and initialised {@link LoRaHAT}
     * @param ownAddress   the 16-bit LoRa address this gateway uses as the
     *                     {@code origin} on outbound packets
     */
    public LoRaProtocol(LoRaHAT hat, int ownAddress) throws HandlerRegistrationException {
        super(PROTOCOL_NAME, PROTOCOL_ID);
        this.hat = hat;
        this.ownAddress = ownAddress & 0xFFFF;

        registerRequestHandler(SendLoRaPacketRequest.REQUEST_ID, this::uponSendRequest);
        registerRequestHandler(BroadcastLoRaRequest.REQUEST_ID, this::uponBroadcastRequest);
    }

    @Override
    public void init(Properties props) {
        // The LoRaHAT reader thread is already running; wire it into Babel.
        // triggerNotification is safe from foreign threads (Babel.triggerNotification
        // posts to each subscriber's LinkedBlockingQueue).
        hat.setPacketHandler(this::deliverIncoming);
    }

    private void uponSendRequest(SendLoRaPacketRequest req, short sourceProto) {
        transmit(req.getDestAddress(), req.getSourceProto(), req.getPayload());
    }

    private void uponBroadcastRequest(BroadcastLoRaRequest req, short sourceProto) {
        transmit(BROADCAST_ADDR, req.getSourceProto(), req.getPayload());
    }

    private void transmit(int destAddress, short sourceProto, byte[] payload) {
        if (payload.length > MAX_USER_PAYLOAD_BYTES) {
            triggerNotification(new LoRaSendFailedNotification(sourceProto, destAddress,
                    "Payload " + payload.length + "B exceeds MTU " + MAX_USER_PAYLOAD_BYTES + "B"));
            return;
        }

        byte[] enveloped = new byte[SOURCE_PROTO_ENVELOPE_BYTES + payload.length];
        enveloped[0] = (byte) ((sourceProto >> 8) & 0xFF);
        enveloped[1] = (byte) (sourceProto & 0xFF);
        System.arraycopy(payload, 0, enveloped, SOURCE_PROTO_ENVELOPE_BYTES, payload.length);

        try {
            LoRaPacket packet = new LoRaPacket.Builder()
                    .origin(ownAddress)
                    .previousHop(ownAddress)
                    .destination(destAddress)
                    .payload(enveloped)
                    .build();
            hat.transmit(packet);
        } catch (Exception e) {
            logger.warn("LoRa transmit failed for sourceProto={} dest=0x{}: {}",
                        sourceProto, String.format("%04X", destAddress), e.toString());
            triggerNotification(new LoRaSendFailedNotification(sourceProto, destAddress, e.toString()));
        }
    }

    private void deliverIncoming(LoRaPacket packet) {
        byte[] enveloped = packet.getPayload();
        if (enveloped == null || enveloped.length < SOURCE_PROTO_ENVELOPE_BYTES) {
            // Foreign sender that doesn't speak our envelope — silently drop.
            return;
        }
        short sourceProto = (short) (((enveloped[0] & 0xFF) << 8) | (enveloped[1] & 0xFF));
        byte[] payload = new byte[enveloped.length - SOURCE_PROTO_ENVELOPE_BYTES];
        System.arraycopy(enveloped, SOURCE_PROTO_ENVELOPE_BYTES, payload, 0, payload.length);

        triggerNotification(new LoRaPacketReceivedNotification(
                sourceProto,
                packet.getOriginAddr(),
                packet.getPrevHopAddr(),
                packet.getDestAddr(),
                packet.getRssi(),
                payload));
    }
}
