package pt.paradigmshift.babel.lora.requests;

import pt.unl.fct.di.novasys.babel.generic.ProtoRequest;

/**
 * Asks the LoRa protocol to transmit a payload to a specific 16-bit LoRa
 * destination address. Multiple Babel protocols can share the radio safely by
 * tagging each request with their own {@code sourceProto} (their numeric
 * {@code PROTOCOL_ID}) — receivers see that identifier on the inbound
 * {@link pt.paradigmshift.babel.lora.notifications.LoRaPacketReceivedNotification}
 * and filter accordingly.
 */
public class SendLoRaPacketRequest extends ProtoRequest {

    public static final short REQUEST_ID = 1100;

    private final short sourceProto;
    private final int destAddress;
    private final byte[] payload;

    /**
     * @param sourceProto numeric ID of the sending protocol ({@code PROTOCOL_ID})
     * @param destAddress 16-bit LoRa destination address (0..0xFFFF)
     * @param payload     bytes to transmit; must fit within the LoRa MTU
     */
    public SendLoRaPacketRequest(short sourceProto, int destAddress, byte[] payload) {
        super(REQUEST_ID);
        this.sourceProto = sourceProto;
        this.destAddress = destAddress & 0xFFFF;
        this.payload = payload;
    }

    public short getSourceProto() { return sourceProto; }

    public int getDestAddress() { return destAddress; }

    public byte[] getPayload() { return payload; }
}
