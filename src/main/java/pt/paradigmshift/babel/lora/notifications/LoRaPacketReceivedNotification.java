package pt.paradigmshift.babel.lora.notifications;

import pt.unl.fct.di.novasys.babel.generic.ProtoNotification;

/**
 * Delivered to every protocol that subscribes to it, for every LoRa packet
 * received by the radio. Subscribers are expected to filter on {@link #getAppId()}
 * to keep only the traffic addressed to their application identifier.
 */
public class LoRaPacketReceivedNotification extends ProtoNotification {

    public static final short NOTIFICATION_ID = 1100;

    private final short appId;
    private final int originAddress;
    private final int prevHopAddress;
    private final int destAddress;
    private final int rssi;
    private final byte[] payload;

    public LoRaPacketReceivedNotification(short appId, int originAddress, int prevHopAddress,
                                          int destAddress, int rssi, byte[] payload) {
        super(NOTIFICATION_ID);
        this.appId = appId;
        this.originAddress = originAddress & 0xFFFF;
        this.prevHopAddress = prevHopAddress & 0xFFFF;
        this.destAddress = destAddress & 0xFFFF;
        this.rssi = rssi;
        this.payload = payload;
    }

    public short getAppId() { return appId; }

    public int getOriginAddress() { return originAddress; }

    public int getPrevHopAddress() { return prevHopAddress; }

    public int getDestAddress() { return destAddress; }

    public int getRssi() { return rssi; }

    public byte[] getPayload() { return payload; }
}
