package com.gesits.dashboard;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit test untuk parser FardriverProtocol & pencocokan CRC16 terhadap tabel referensi
 * jackhumbert/fardriver-controllers (MIT) / bobecek79/ESP32-Fardriver-BLE-Reader.
 */
public class FardriverProtocolTest {

    private static byte[] buildSignedFrame(int idWithFlags, byte[] data12) {
        byte[] frame = new byte[16];
        frame[0] = (byte) 0xAA;
        frame[1] = (byte) (idWithFlags & 0xFF);
        System.arraycopy(data12, 0, frame, 2, Math.min(12, data12.length));
        int crc = FardriverProtocol.computeCrcTable(frame, 14);
        frame[14] = (byte) (crc & 0xFF);
        frame[15] = (byte) ((crc >>> 8) & 0xFF);
        return frame;
    }

    @Test
    public void crcTable_matchesBitwisePolyA001AndInit7F3C_forAllBytes() {
        assertEquals(0xC1, FardriverProtocol.CRC_TABLE_HI[1]);
        assertEquals(0xC0, FardriverProtocol.CRC_TABLE_LO[1]);
        assertEquals(0x01, FardriverProtocol.CRC_TABLE_HI[0x80]);
        assertEquals(0xA0, FardriverProtocol.CRC_TABLE_LO[0x80]);

        for (int b = 0; b < 256; b++) {
            byte[] buf = new byte[]{(byte) 0xAA, (byte) b, 0x12, 0x34, 0x56, 0x78, (byte) 0x9A, 0x00,
                0x01, 0x02, 0x03, 0x04, 0x05, 0x06};
            int tableCrc = FardriverProtocol.computeCrcTable(buf, 14);
            int bitCrc = FardriverProtocol.computeCrcBitwise(buf, 14, 0x7F3C);
            assertEquals("Mismatch on byte=" + b, bitCrc, tableCrc);
        }
    }

    @Test
    public void flashReadAddrTable_mapsExpectedIdsAndRejectsCorruptFrames() {
        assertEquals(55, FardriverProtocol.FLASH_READ_ADDR.length);
        assertEquals(0xE2, FardriverProtocol.FLASH_READ_ADDR[0]);
        assertEquals(0xE8, FardriverProtocol.FLASH_READ_ADDR[1]);
        assertEquals(0xEE, FardriverProtocol.FLASH_READ_ADDR[2]);
        assertEquals(0xD0, FardriverProtocol.FLASH_READ_ADDR[47]);
        assertEquals(0xD6, FardriverProtocol.FLASH_READ_ADDR[51]);
        assertEquals(0xF4, FardriverProtocol.FLASH_READ_ADDR[53]);
        assertEquals(0xFA, FardriverProtocol.FLASH_READ_ADDR[54]);

        byte[] frame = buildSignedFrame(0x80, new byte[12]); // id=0 (0xE2), flag=2
        assertTrue(FardriverProtocol.verifyFrameCrc(frame));

        // Corrupt 1 byte -> must fail
        frame[5] ^= 0x01;
        assertFalse(FardriverProtocol.verifyFrameCrc(frame));

        // Out-of-range id (>=55) -> must reject
        byte[] badIdFrame = buildSignedFrame(55, new byte[12]);
        assertFalse(FardriverProtocol.parseFrame(badIdFrame, new FardriverProtocol.FardriverState(), new FardriverProtocol.SpeedConfig()));
    }

    @Test
    public void parseE2_decodesFlagsBrakeErrorSignedSpeedAndManualFormula() {
        FardriverProtocol.FardriverState state = new FardriverProtocol.FardriverState();
        FardriverProtocol.SpeedConfig cfg = new FardriverProtocol.SpeedConfig();
        cfg.autoMode = false;
        cfg.polePairs = 20;
        cfg.gearRatio = 1.0;
        cfg.wheelCircMm = 1450.0;

        byte[] d = new byte[12];
        // byte0: bit0 D=1, bit1 R=0, gear=2 (bits2-3 = 0b10 -> 0x08) => 0x09
        d[0] = 0x09;
        // byte2-3: error=0x0042, byte3 bit7 brake=1 => d[2]=0x42, d[3]=0x80
        d[2] = 0x42;
        d[3] = (byte) 0x80;
        // byte6-7: raw MeasureSpeed = -1500 (0xFA24 little endian) -> |raw|=1500
        int raw = -1500;
        d[6] = (byte) (raw & 0xFF);
        d[7] = (byte) ((raw >> 8) & 0xFF);

        // Use id=7 (also maps to 0xE2) with flag=2 -> 0x87
        assertTrue(FardriverProtocol.parseFrame(buildSignedFrame(0x87, d), state, cfg));
        assertEquals(0xE2, state.lastAddr);
        assertTrue(state.drive);
        assertFalse(state.reverse);
        assertEquals(2, state.gear);
        assertEquals(0x0042, state.errorMask);
        assertTrue(state.brake);
        assertEquals(-1500, state.rawMeasureSpeed);
        // rpm = |raw| * 4 / 20 = 300
        assertEquals(300, state.rpm);
        // speed = 1500 * 4 / 20 / 1.0 * 1.45 * 60 / 1000 = 26.1 km/h
        assertEquals(26.1, state.speedKmh, 0.05);
    }

    @Test
    public void parseE8_decodesVoltageAndSignedBatteryCurrent() {
        FardriverProtocol.FardriverState state = new FardriverProtocol.FardriverState();
        FardriverProtocol.SpeedConfig cfg = new FardriverProtocol.SpeedConfig();
        cfg.invertCurrent = false;

        byte[] d = new byte[12];
        // byte0-1: 724 -> 72.4 V
        d[0] = (byte) (724 & 0xFF);
        d[1] = (byte) ((724 >> 8) & 0xFF);
        // byte4-5: +100 -> 25.0 A discharge -> -25.0 A in app sign convention
        d[4] = 100;
        d[5] = 0;

        assertTrue(FardriverProtocol.parseFrame(buildSignedFrame(8, d), state, cfg)); // id=8 -> 0xE8
        assertEquals(0xE8, state.lastAddr);
        assertEquals(72.4, state.voltageV, 0.01);
        assertEquals(-25.0, state.batteryCurrentA, 0.01);

        cfg.invertCurrent = true;
        assertTrue(FardriverProtocol.parseFrame(buildSignedFrame(8, d), state, cfg));
        assertEquals(25.0, state.batteryCurrentA, 0.01);
    }

    @Test
    public void parseD0_D6_F4_EE_decodesTempsSocPhaseAndAutoSpeed() {
        FardriverProtocol.FardriverState state = new FardriverProtocol.FardriverState();
        FardriverProtocol.SpeedConfig cfg = new FardriverProtocol.SpeedConfig();
        cfg.autoMode = true;

        // 0xD0 (id=47): 120/70-12, RateRatio=1000 (1.000)
        byte[] d0 = new byte[12];
        d0[4] = 70;   // WheelRatio
        d0[5] = 12;   // WheelRadius
        d0[7] = 120;  // WheelWidth
        d0[8] = (byte) (1000 & 0xFF);
        d0[9] = (byte) ((1000 >> 8) & 0xFF);
        assertTrue(FardriverProtocol.parseFrame(buildSignedFrame(47, d0), state, cfg));
        assertEquals(70, state.wheelRatio);
        assertEquals(12, state.wheelRadius);
        assertEquals(120, state.wheelWidth);
        assertEquals(1000, state.rateRatio);

        // 0xE2 (id=0): raw=500 -> auto speed ~ 44.6 km/h
        byte[] e2 = new byte[12];
        e2[6] = (byte) (500 & 0xFF);
        e2[7] = (byte) ((500 >> 8) & 0xFF);
        assertTrue(FardriverProtocol.parseFrame(buildSignedFrame(0, e2), state, cfg));
        assertEquals(44.6, state.speedKmh, 0.2);

        // 0xD6 (id=51): byte10-11 MOSFET temp = 48 C
        byte[] d6 = new byte[12];
        d6[10] = 48;
        d6[11] = 0;
        assertTrue(FardriverProtocol.parseFrame(buildSignedFrame(51, d6), state, cfg));
        assertEquals(48, state.controllerTempC);

        // 0xF4 (id=53): byte0-1 motor temp = 62 C, byte3 SOC = 84%
        byte[] f4 = new byte[12];
        f4[0] = 62;
        f4[1] = 0;
        f4[3] = 84;
        assertTrue(FardriverProtocol.parseFrame(buildSignedFrame(53, f4), state, cfg));
        assertEquals(62, state.motorTempC);
        assertEquals(84, state.socPct);

        // 0xEE (id=2): phase A = +80 (20.0 A), phase C = -40 (-10.0 A)
        byte[] ee = new byte[12];
        ee[0] = 80;
        int phC = -40;
        ee[4] = (byte) (phC & 0xFF);
        ee[5] = (byte) ((phC >> 8) & 0xFF);
        ee[6] = (byte) ((phC >> 16) & 0xFF);
        assertTrue(FardriverProtocol.parseFrame(buildSignedFrame(2, ee), state, cfg));
        assertEquals(20.0, state.phaseCurrentA, 0.01);
        assertEquals(-10.0, state.phaseCurrentC, 0.01);
    }
}
