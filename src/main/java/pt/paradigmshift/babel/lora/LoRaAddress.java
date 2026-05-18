package pt.paradigmshift.babel.lora;

import pt.paradigmshift.babel.radio.RadioAddress;

/**
 * LoRa peer identifier: a 16-bit on-air address as used by the EByte
 * E22 / SX126X HAT (0x0000 .. 0xFFFE for unicast, 0xFFFF reserved for
 * broadcast — applications should send broadcasts via
 * {@link pt.paradigmshift.babel.radio.requests.BroadcastRadioPacketRequest}
 * rather than constructing a {@code LoRaAddress(0xFFFF)}).
 */
public final class LoRaAddress extends RadioAddress {

    private final int addr;

    public LoRaAddress(int addr) {
        this.addr = addr & 0xFFFF;
    }

    /** The 16-bit on-air address, in {@code 0 .. 0xFFFF}. */
    public int getAddress() { return addr; }

    @Override
    protected Object key() { return addr; }

    @Override
    public short owningProtocolId() { return LoRaProtocol.PROTOCOL_ID; }

    @Override
    public String toString() {
        return String.format("LoRaAddress[0x%04X]", addr);
    }
}
