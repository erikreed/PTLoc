/*
 * Mount.java
 *
 * Created on Oct 4, 2011 2:06:44 PM;
 */
package org.sunspotworld;

import com.sun.spot.peripheral.II2C;
import com.sun.spot.peripheral.radio.RadioFactory;
import com.sun.spot.resources.Resources;
import com.sun.spot.resources.transducers.IAccelerometer3D;
import com.sun.spot.resources.transducers.IIOPin;
import com.sun.spot.resources.transducers.ISwitch;
import com.sun.spot.resources.transducers.ISwitchListener;
import com.sun.spot.resources.transducers.ITriColorLED;
import com.sun.spot.resources.transducers.ITriColorLEDArray;
import com.sun.spot.resources.transducers.SwitchEvent;
import com.sun.spot.sensorboard.EDemoBoard;
import com.sun.spot.service.BootloaderListenerService;
import com.sun.spot.util.IEEEAddress;
import com.sun.spot.util.Utils;
import java.io.DataOutputStream;
import java.io.IOException;
import javax.microedition.io.Connector;
import javax.microedition.io.StreamConnection;
import javax.microedition.midlet.MIDlet;
import javax.microedition.midlet.MIDletStateChangeException;

public class Pointer extends MIDlet implements ISwitchListener {

    public static final int[] LIGHT_BLUE = new int[]{100, 100, 100}; //hour color
    public static final int[] GREEN = new int[]{0, 128, 0}; // minute
    public static final int[] GOLD = new int[]{255, 215, 0}; // second
    public static final int[] RED = new int[]{255, 0, 0}; // error color
    public static final boolean MOUNT = false;
    public static final String DEST_IEEE = "0014.4F01.0000.267D:100"; //B5
    public static final String SRC_IEEE = "0014.4F01.0000.33A5:100"; //B8
    public static final boolean DO_CALIBRATE = false;
    public static final byte[] CMD_MEASURE = hexStringToByteArray("0031");
    public static final byte[] CMD_CAL_START = hexStringToByteArray("00C0");
    public static final byte[] CMD_CAL_END = hexStringToByteArray("00C1");
    public static final byte[] CMD_RESET = hexStringToByteArray("AOAAA5C5");
    //last 00 is address
    public static final byte[] CMD_CHANGE_BUS = hexStringToByteArray("A0AAA500");
    //last 00 are bits to set
    public static final byte[] CMD_SET_HIGH_BITS = hexStringToByteArray("0300");
    public static final byte[] CMD_SET_LOW_BITS = hexStringToByteArray("0400");
    public static final int DISTANCE_TRIGGER_TIMEOUT = 1; //1us
    public static final int DISTANCE_ECHO_TIMEOUT = 50; //50us 
    public static final int I2C_ADDRESS = 0xE0;
    public static final int NUM_AVG_READINGS = 5;
    public static final int CALIBRATION_TIME_MS = 120000;
    private II2C i2c = EDemoBoard.getInstance().getI2C();
    private ISwitch sw1;
    private ISwitch sw2;
    private DataOutputStream out;
    private ITriColorLEDArray leds = (ITriColorLEDArray) Resources.lookup(ITriColorLEDArray.class);
    private IIOPin D1;
    private IIOPin D0;
    private EDemoBoard board;
    private IAccelerometer3D acc;
    private boolean sendingMessage = false;

    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    protected void startApp() throws MIDletStateChangeException {
        board = EDemoBoard.getInstance();
        D1 = board.getIOPins()[EDemoBoard.D1];
        D0 = board.getIOPins()[EDemoBoard.D0];
        acc = (IAccelerometer3D) Resources.lookup(IAccelerometer3D.class);
        
        System.out.println("Running pointer!");
        BootloaderListenerService.getInstance().start();   // monitor the USB (if connected) and recognize commands from host

        long ourAddr = RadioFactory.getRadioPolicyManager().getIEEEAddress();
        System.out.println("radio address = " + IEEEAddress.toDottedHex(ourAddr));

        //IDemoBoard board = (IDemoBoard)Resources.lookup(IDemoBoard.class);

        try {
            StreamConnection conn = (StreamConnection) Connector.open(
                    "radiostream://" + DEST_IEEE);
            out = conn.openDataOutputStream();
            System.out.println("Listening on switch 1 and 2.");
            sw1 = (ISwitch) Resources.lookup(ISwitch.class, "SW1");
            sw2 = (ISwitch) Resources.lookup(ISwitch.class, "SW2");
            sw1.addISwitchListener(this);
            sw2.addISwitchListener(this);
            ITriColorLED led = leds.getLED(0);
            led.setRGB(100, 100, 100);


            i2c.open();

            if (DO_CALIBRATE) {
                calibrate();
                notifyDestroyed(); 
                i2c.close();
                out.close();
                return;
            }
            //i2c.setClockSpeed(9800);
            for (int i = 0; i < 1000; i++) {
                double angle = getAvgAngle();
                double dist = getAvgDist();
                System.out.println("angle: " + Double.toString(angle)
                        + "\tdist: " + dist
                        + "\ttilt: " + acc.getTiltY());

                led.setOn();                        // Blink LED
                Utils.sleep(1000);                   // wait 1/4 seconds 
                led.setOff();
                Utils.sleep(1000);
            }

            out.close();
            i2c.close();
        } catch (IOException e) {
            System.err.println(e);
            flashAllLEDs(RED, 1000);
            e.printStackTrace();
        }
        finally {
            notifyDestroyed();                      // cause the MIDlet to exit
        }
    }

    /**
     * Fires if in set time mode and one of the switches was pressed.
     * @param se Switch event.
     */
    public void switchPressed(SwitchEvent se) {
        //sw1 increases position
        //sw2 decreases position
        if (sendingMessage) {
            System.err.println("previous message still being sent.");
            return;
        }
        try {
            if (se.getSwitch().equals(sw1)) {
                //while (sw1.isClosed()) {
                sendingMessage = true;
                double dist = getAvgDist();
                double angle = getAvgAngle();
                double tilt = acc.getTiltY();
                System.out.println("Switch 1 pressed. Sending:");
                System.out.println("Type=1\t d1=" + dist + "\t angle1=" + angle + 
                        "\ttilt: " + tilt);
                flashAllLEDs(LIGHT_BLUE, 250);
                out.write(1); // 1 == set mount distance
                out.writeDouble(dist);
                out.writeDouble(angle);
                out.writeDouble(tilt);
                out.flush();
                flashAllLEDs(GREEN, 250);
                System.out.println("Sending successful.");
                //sendingMessage = false;
                //}
                //out.close();

            } else if (se.getSwitch().equals(sw2)) {
                //while (sw2.isClosed()) {
                sendingMessage = true;
                double dist = getAvgDist();
                double angle = getAvgAngle();
                double tilt = acc.getTiltY();
                flashAllLEDs(GOLD, 250);
                System.out.println("Switch 2 pressed. Sending:");
                System.out.println("Type=2\t d2=" + dist + "\t angle2=" + angle
                        + "\ttilt: " + tilt);
                out.write(2); // 2 == set destination distance
                out.writeDouble(dist);
                out.writeDouble(angle);
                out.writeDouble(tilt);
                out.flush();
                flashAllLEDs(GREEN, 250);
                System.out.println("Sending successful.");

                //sendingMessage = false;
                //}
            }

        } catch (IOException e) {
            e.printStackTrace();
            flashAllLEDs(RED, 1000);
        }
        finally {
            sendingMessage = false;
        }
    }

    /**
     * Fires when switch released. Unused.
     * @param se Switch event. Unused.
     */
    public void switchReleased(SwitchEvent se) {
    }

    protected void pauseApp() {
    }

    protected void destroyApp(boolean unconditional) throws MIDletStateChangeException {
    }

//    private void TestDist(ITriColorLED led, int get, int set) {
//        System.out.println("set pulse: " + set + "us, get pulse: " + get + "us");
//        for (int i = 0; i < 10; i++) {
//            led.setOn();
//            int dist = getDistance(set, get);
//            System.out.print("dist: " + dist);
//            System.out.println("\tdist/148: " + dist / 148.0);
//            Utils.sleep(350);
//            led.setOff();
//            Utils.sleep(350);
//        }
//    }
    //start = 1us
    //get = 50us is fine
    int getDistance() {
        board.startPulse(D1, true, DISTANCE_TRIGGER_TIMEOUT);
        int ptime = board.getPulse(D0, true, DISTANCE_ECHO_TIMEOUT);
        while (ptime == 0) {
            Utils.sleep(10);
            board.startPulse(D1, true, DISTANCE_TRIGGER_TIMEOUT);
            ptime = board.getPulse(D0, true, DISTANCE_ECHO_TIMEOUT);
        }
        return ptime;
        //return 1130 * 12 * ptime / (2 * 1000000); 
    }

    private void calibrate() throws java.io.IOException {
        i2c.write(0xE0, CMD_CAL_START, 0, 2);
        System.out.println("Calibrating...");
        Utils.sleep(CALIBRATION_TIME_MS); // 2 minutes
        i2c.write(0xE0, CMD_CAL_END, 0, 2);
        System.out.println("Calibrating complete...");
    }

//    read(int slaveAddress, byte[] data, int off, int len) 
//          Read data from the specified I2C slave device.
//    write(int slaveAddress, byte[] data, int off, int len) 
//          Write data to the specified I2C slave device.
    //default factory address = 0xE0
    private double getAngle() throws java.io.IOException {
        // byte layout --
        //        0x0D
        //        0x0A
        //        0x30~0x33
        //        0x30~0x39
        //        0x30~0x39
        //        0x2E
        //        0x30~0x39
        //        0x00~0xFF
        byte[] data = new byte[8];

        i2c.write(I2C_ADDRESS, CMD_MEASURE, 0, 2);
        i2c.read(I2C_ADDRESS, data, 0, 8);

//        for (int i = 0; i < data.length; i++) {
//            System.out.print(data[i] + " ");
//        }
//
//        System.out.println();
        // val 1-3
//        byte angle_hundreds = (byte) (data[2] - 0x30);
//        
//        //vals 1-9
//        byte angle_tens = (byte) (data[3] - 0x30);
//        byte angle_bit = (byte) (data[4] - 0x30);
//        int angle = angle_hundreds*100 + angle_tens*10
//                + angle_bit;
        int upperAngle = 0xFF & data[1];
        int lowerAngle = 0xFF & data[2];
        double angle = (upperAngle << 8) + lowerAngle;
        upperAngle = 0xFF & data[3];
        lowerAngle = 0xFF & data[4];
        // unused
        //int declinationAngle = (upperAngle << 8) + lowerAngle;
        angle /= 10.0;
        angle -= 90;
        if (angle < 0)
            angle += 360;
        return angle;
    }

    private double getAvgAngle() throws IOException {
        //double[] angles = new double[num];
        for (int i=0; i<2; i++)
            getAngle();
        double sum = 0;
        double min = Double.MAX_VALUE;
        for (int i = 0; i < NUM_AVG_READINGS; i++) {
            double angle = getAngle();
            sum += angle;
            min = Math.min(min, angle);
        }
        sum -= min;
        return sum / (NUM_AVG_READINGS-1);
    }

    private double getAvgDist() throws IOException {
        //double[] angles = new double[num];
        int sum = 0;
        for (int i = 0; i < NUM_AVG_READINGS; i++) {
            sum += getDistance();
        }
        return sum / (double) NUM_AVG_READINGS;
    }

    /**
     * Flashes all the LEDs
     * @param rgb Color to display
     * @param duration Duration to display color
     */
    private void flashAllLEDs(int rgb[], int duration) {
        for (int i = 0; i < leds.size(); i++) {
            leds.getLED(i).setRGB(rgb[0], rgb[1], rgb[2]);
            leds.getLED(i).setOn();
        }
        Utils.sleep(duration);
        for (int i = 0; i < leds.size(); i++) {
            leds.getLED(i).setOff();
        }
    }
}
