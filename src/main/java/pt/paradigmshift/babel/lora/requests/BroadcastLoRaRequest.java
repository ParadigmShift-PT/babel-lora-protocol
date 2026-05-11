package pt.paradigmshift.babel.lora.requests;

import pt.unl.fct.di.novasys.babel.generic.ProtoRequest;

/**
 * Asks the LoRa protocol to broadcast a payload to every LoRa peer in range
 * (destination address {@code 0xFFFF}). Tag the request with an {@code appId}
 * (commonly the sender protocol id) so receiving protocols can demultiplex the
 * traffic.
 */
public class BroadcastLoRaRequest extends ProtoRequest {

    public static final short REQUEST_ID = 1101;

    private final short appId;
    private final byte[] payload;

    /**
     * @param appId   application identifier (commonly the sender protocol id)
     * @param payload bytes to transmit; must fit within the LoRa MTU
     */
    public BroadcastLoRaRequest(short appId, byte[] payload) {
        super(REQUEST_ID);
        this.appId = appId;
        this.payload = payload;
    }

    public short getAppId() { return appId; }

    public byte[] getPayload() { return payload; }
}
