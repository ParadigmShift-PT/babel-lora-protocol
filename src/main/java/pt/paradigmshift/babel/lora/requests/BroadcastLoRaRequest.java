package pt.paradigmshift.babel.lora.requests;

import pt.unl.fct.di.novasys.babel.generic.ProtoRequest;

/**
 * Asks the LoRa protocol to broadcast a payload to every LoRa peer in range
 * (destination address {@code 0xFFFF}). Tag the request with the sending
 * protocol's {@code sourceProto} ({@code PROTOCOL_ID}) so receiving protocols
 * can demultiplex the traffic.
 */
public class BroadcastLoRaRequest extends ProtoRequest {

    public static final short REQUEST_ID = 1101;

    private final short sourceProto;
    private final byte[] payload;

    /**
     * @param sourceProto numeric ID of the sending protocol ({@code PROTOCOL_ID})
     * @param payload     bytes to transmit; must fit within the LoRa MTU
     */
    public BroadcastLoRaRequest(short sourceProto, byte[] payload) {
        super(REQUEST_ID);
        this.sourceProto = sourceProto;
        this.payload = payload;
    }

    public short getSourceProto() { return sourceProto; }

    public byte[] getPayload() { return payload; }
}
