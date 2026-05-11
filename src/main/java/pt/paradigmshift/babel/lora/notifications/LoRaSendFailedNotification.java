package pt.paradigmshift.babel.lora.notifications;

import pt.unl.fct.di.novasys.babel.generic.ProtoNotification;

/**
 * Delivered when a send/broadcast request could not be transmitted (typically
 * because the payload exceeded the LoRa MTU, or the underlying radio threw).
 * Subscribers filter on {@link #getAppId()} just as for inbound packets.
 */
public class LoRaSendFailedNotification extends ProtoNotification {

    public static final short NOTIFICATION_ID = 1101;

    private final short appId;
    private final int destAddress;
    private final String reason;

    public LoRaSendFailedNotification(short appId, int destAddress, String reason) {
        super(NOTIFICATION_ID);
        this.appId = appId;
        this.destAddress = destAddress & 0xFFFF;
        this.reason = reason;
    }

    public short getAppId() { return appId; }

    public int getDestAddress() { return destAddress; }

    public String getReason() { return reason; }
}
