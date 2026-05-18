package pt.paradigmshift.babel.lora.notifications;

import pt.paradigmshift.babel.lora.LoRaAddress;
import pt.paradigmshift.babel.radio.notifications.RadioPacketReceivedNotification;

/**
 * Specialisation of
 * {@link RadioPacketReceivedNotification} carrying the LoRa-specific
 * metadata available on every received frame: previous-hop address,
 * frame destination, channel, and RSSI.
 *
 * <p>Shares
 * {@link RadioPacketReceivedNotification#NOTIFICATION_ID} with the base —
 * subscribers of the base ID receive this subclass too. Cast at the handler
 * if you need the LoRa extras:
 *
 * <pre>{@code
 * if (n instanceof LoRaPacketReceivedNotification lo) {
 *     int rssi = lo.getRssi();
 * }
 * }</pre>
 */
public class LoRaPacketReceivedNotification
        extends RadioPacketReceivedNotification {

    private final LoRaAddress prevHop;
    private final LoRaAddress destination;
    private final int channel;
    private final int rssi;

    public LoRaPacketReceivedNotification(short sourceProto,
                                          LoRaAddress origin,
                                          LoRaAddress prevHop,
                                          LoRaAddress destination,
                                          int channel,
                                          int rssi,
                                          byte[] payload) {
        super(sourceProto, origin, payload);
        this.prevHop = prevHop;
        this.destination = destination;
        this.channel = channel;
        this.rssi = rssi;
    }

    public LoRaAddress getPrevHop() { return prevHop; }

    /**
     * Frame destination. For broadcast frames this is
     * {@code new LoRaAddress(0xFFFF)}.
     */
    public LoRaAddress getDestination() { return destination; }

    /** E22 channel index — frequency is {@code 850.125 + channel} MHz. */
    public int getChannel() { return channel; }

    /** Received signal strength in dBm. {@code 0} when the radio has not
     *  appended an RSSI byte to the frame (see the driver's
     *  {@code packetRssi} setting). */
    public int getRssi() { return rssi; }

    /** Convenience accessor: {@link #getOrigin()} cast to {@link LoRaAddress}. */
    public LoRaAddress getLoRaOrigin() { return (LoRaAddress) getOrigin(); }
}
