package pt.paradigmshift.babel.lora.notifications;

import pt.unl.fct.di.novasys.babel.generic.ProtoNotification;

/**
 * Delivered when a send/broadcast request could not be transmitted (typically
 * because the payload exceeded the LoRa MTU, or the underlying radio threw).
 * Subscribers filter on {@link #getSourceProto()} (the requester's
 * {@code PROTOCOL_ID}) just as for inbound packets.
 */
public class LoRaSendFailedNotification extends ProtoNotification {

    public static final short NOTIFICATION_ID = 1101;

    private final short sourceProto;
    private final int destAddress;
    private final String reason;

    public LoRaSendFailedNotification(short sourceProto, int destAddress, String reason) {
        super(NOTIFICATION_ID);
        this.sourceProto = sourceProto;
        this.destAddress = destAddress & 0xFFFF;
        this.reason = reason;
    }

    public short getSourceProto() { return sourceProto; }

    public int getDestAddress() { return destAddress; }

    public String getReason() { return reason; }
}
