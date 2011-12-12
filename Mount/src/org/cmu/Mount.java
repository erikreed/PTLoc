/**
 * Erik Reed
 * Carnegie Mellon University
 * 12/12/11
 */
package org.cmu;

import com.sun.spot.peripheral.II2C;
import com.sun.spot.peripheral.radio.RadioFactory;
import com.sun.spot.resources.Resources;
import com.sun.spot.resources.transducers.IAccelerometer3D;
import com.sun.spot.resources.transducers.ISwitch;
import com.sun.spot.resources.transducers.ISwitchListener;
import com.sun.spot.resources.transducers.ITriColorLED;
import com.sun.spot.resources.transducers.ITriColorLEDArray;
import com.sun.spot.resources.transducers.SwitchEvent;
import com.sun.spot.sensorboard.EDemoBoard;
import com.sun.spot.sensorboard.IDemoBoard;
import com.sun.spot.sensorboard.peripheral.Servo;
import com.sun.spot.service.BootloaderListenerService;
import com.sun.spot.util.IEEEAddress;
import com.sun.spot.util.Utils;

import java.io.DataInputStream;
import java.io.IOException;
import javax.microedition.io.Connector;
import javax.microedition.io.StreamConnection;
import javax.microedition.midlet.MIDlet;
import javax.microedition.midlet.MIDletStateChangeException;

/**
 * Point to Location Framework (PTLoc) -- Mount Device
 * @author Erik Reed
 */
public class Mount extends MIDlet implements ISwitchListener {

    public static final String DEST_IEEE = "0014.4F01.0000.267D:100"; //B5
    public static final String SRC_IEEE = "0014.4F01.0000.33A5:100"; //B8
    public static final int[] LIGHT_BLUE = new int[]{100, 100, 100}; //hour color
    public static final int[] GREEN = new int[]{0, 128, 0}; // minute
    public static final int[] GOLD = new int[]{255, 215, 0}; // second
    public static final int[] RED = new int[]{255, 0, 0}; // error color
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
    public static final boolean USE_COMPASS = true;
    public static final boolean TEST_GO_TO_LOCATION = false;
    public static final double TEST_TILT = 0.15; // 0-1 float. negative -> tilting upwards
    public static final double TEST_ANGLE = 36.0;
    public static final double TILT_TOLERANCE = 0.01; // unit?
    public static final double ANGLE_TOLERANCE = 1; // i.e. +/- 5 degrees
    public static final int MAX_NUM_ITERATIONS = 15;
    private II2C i2c = EDemoBoard.getInstance().getI2C();
    private IAccelerometer3D acc;
    private ITriColorLEDArray leds = (ITriColorLEDArray) Resources.lookup(ITriColorLEDArray.class);
    private ISwitch sw1;
    private ISwitch sw2;
    private Servo servo1;
    private Servo servo2;
    private float starting_servo_position;
    private float pServo1;
    private float pServo2;
    private boolean receivedType1 = false;
    private double receivedD1 = -1;
    private double receivedD2 = -1;
    private double receivedAngle1 = -1;
    private double receivedAngle2 = -1;
    private double receivedTilt1 = -1;
    private double receivedTilt2 = -1;
    private boolean moving = false;

    /**
     * Returns the hex string as byte array.
     * @param s String to translate.
     * @return Byte array
     */
    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    /**
     * Runs the Mount
     * @throws MIDletStateChangeException 
     */
    protected void startApp() throws MIDletStateChangeException {
        System.out.println("Running mount.\n");
        BootloaderListenerService.getInstance().start();   // monitor the USB (if connected) and recognize commands from host

        long ourAddr = RadioFactory.getRadioPolicyManager().getIEEEAddress();
        System.out.println("radio address = " + IEEEAddress.toDottedHex(ourAddr));

        IDemoBoard board = (IDemoBoard) Resources.lookup(IDemoBoard.class);
        EDemoBoard eDemo = EDemoBoard.getInstance();
        acc = (IAccelerometer3D) Resources.lookup(IAccelerometer3D.class);

        sw1 = EDemoBoard.getInstance().getSwitches()[EDemoBoard.SW1];
        sw2 = EDemoBoard.getInstance().getSwitches()[EDemoBoard.SW2];
        servo1 = new Servo(eDemo.getOutputPins()[EDemoBoard.H1]);
        servo2 = new Servo(eDemo.getOutputPins()[EDemoBoard.H2]);
        float delta = .01f;
        float tolerance = 5e-4f;
        starting_servo_position = .5f;
        //bounds for larger servo at 4.8V
        servo1.setBounds(0, 2600);
        servo1.setPosition(starting_servo_position);
        servo2.setBounds(0, 2600);
        servo2.setPosition(starting_servo_position);

//        notifyDestroyed();
//        if (true==true) return;
        pServo1 = starting_servo_position;
        pServo2 = starting_servo_position;
        servo1.setPosition(pServo1);
        servo2.setPosition(pServo2);
        flashAllLEDs(GREEN, 1000);
        
        //servo1.
        System.out.println("listening on switch 1 and 2");
        sw1.addISwitchListener(this);
        sw2.addISwitchListener(this);
        if (TEST_GO_TO_LOCATION) {
            System.out.println("Testing go-to-location");

            try {
                if (USE_COMPASS) {
                    i2c.open();
                }
                System.out.println("-- Initial configuration -- ");
                System.out.println("Tilt (y-axis): " + acc.getTiltY());
                if (USE_COMPASS) {
                    System.out.println("Angle: " + getAvgAngle());
                }

                System.out.println("\nAttempting to move to:");
                System.out.println("Tilt (y-axis): " + TEST_TILT);
                if (USE_COMPASS) {
                    System.out.println("Angle: " + TEST_ANGLE);
                }

                gotoLocation(TEST_TILT, TEST_ANGLE);
            } catch (IOException e) {
                e.printStackTrace();
            }

            notifyDestroyed();
            return;
        }
        ITriColorLEDArray leds = (ITriColorLEDArray) Resources.lookup(ITriColorLEDArray.class);
        ITriColorLED led = leds.getLED(0);
        led.setRGB(0, 255, 0);
        try {
            System.out.println("opening stream: " + "radiostream://" + SRC_IEEE);
            StreamConnection conn = (StreamConnection) Connector.open(
                    "radiostream://" + SRC_IEEE);
            DataInputStream stream = conn.openDataInputStream();
            if (USE_COMPASS) {
                i2c.open();
            }
            int j = 0;
            while (true) {
                while (stream.available() > 0) {
                    led.setRGB(0, 255, 0);
                    led.setOn();
                    while (stream.available() > 0) {
                        flashAllLEDs(LIGHT_BLUE, 250);
                        int in = stream.read();
                        System.out.println("received type: " + in);
                        // 1 = mount
                        // 2 = dest
                        if (in == 1) {
                            receivedD1 = stream.readDouble();
                            receivedAngle1 = stream.readDouble();
                            receivedTilt1 = stream.readDouble();
                            System.out.println("received: d1=" + receivedD1
                                    + "\t angle1=" + receivedAngle1
                                    + "\t tilt1=" + receivedTilt1);
                            receivedType1 = true;
                        } else if (in == 2) {
                            if (!receivedType1) {
                                System.err.println("haven't received type1 yet");
                                // discard values
                                stream.readDouble();
                                stream.readDouble();
                                stream.readDouble();
                                flashAllLEDs(RED, 1000);
                                break;
                            }
                            moving = true;
                            
                            receivedD2 = stream.readDouble();
                            receivedAngle2 = stream.readDouble();
                            receivedTilt2 = stream.readDouble();

                            //double newTilt = acc.getAccelY();
                            try {
                                double newAngle = calcAngle3(receivedD1, receivedD2, receivedAngle1, receivedAngle2);
                                double newTilt = calcTilt3(receivedD1, receivedD2,receivedTilt1,receivedTilt2);
                                
                                System.out.println("Calculating angle and tilt using values:");
                                System.out.println("a1: " + receivedAngle1);
                                System.out.println("a2: " + receivedAngle2);
                                System.out.println("d1: " + receivedD1);
                                System.out.println("d2: " + receivedD2);
                                System.out.println("t1: " + receivedTilt1);
                                System.out.println("t2: " + receivedTilt2);
                                System.out.println("new mount angle: " + newAngle);
                                System.out.println("new mount tilt: " + newTilt);

                                gotoLocation(newTilt, newAngle);

                            }
                            catch (IllegalArgumentException e) {
                                e.printStackTrace();
                                System.err.println("Invalid angles given or something.");
                                System.err.println("a1: " + receivedAngle1);
                                System.err.println("a2: " + receivedAngle2);
                                System.err.println("d1: " + receivedD1);
                                System.err.println("d2: " + receivedD2);
                                System.err.println("t1: " + receivedTilt1);
                                System.err.println("t2: " + receivedTilt2);
                            }
                            moving = false;
                            receivedType1 = false;
                        } else {
                            System.out.println("disregarding last message...");
                            while (stream.available() > 0) {
                                stream.read();
                            }
                            break;
                        }
                    }
                    Utils.sleep(35);
                }
                led.setOff();
                Utils.sleep(150);
                if (j++ % 15 == 0)
                    System.out.println("angle: " + getAvgAngle() + 
                        "\ttilt: " + acc.getTiltY());
            }
        } catch (java.io.IOException e) {
            System.err.println(e);
            e.printStackTrace();
        }
        notifyDestroyed();                      // cause the MIDlet to exit
    }

    protected void pauseApp() {
        // This is not currently called by the Squawk VM
    }

    /**
     * Used to manually move the servos
     * @param se Switch event.
     */
    public void switchPressed(SwitchEvent se) {
        //sw1 increases position
        //sw2 decreases position
        float delta = .01f;
        float tolerance = 5e-4f;
        try {
            //System.out.println(acc.getTiltX() + "\t" + acc.getTiltY() + "\t" + acc.getTiltZ());
            //System.out.println("tilt.x = " + acc.getTiltX());
            System.out.println("tilt.y = " + acc.getTiltY()); //negative = pointing upwards
            //System.out.println("tilt.z = " + acc.getTiltZ());
            if (USE_COMPASS) {
                System.out.println("angle: " + getAvgAngle());
            }

        } catch (IOException e) {
            e.printStackTrace();
        }

        if (se.getSwitch().equals(sw1)) {
            while (sw1.isClosed()) {
                starting_servo_position = starting_servo_position + delta + tolerance <= 1.0f ? starting_servo_position + delta : 1;
                servo1.setPosition(starting_servo_position);
                servo2.setPosition(starting_servo_position);
                //System.out.println("position = " + Float.toString(starting_servo_position));

                Utils.sleep(25);
            }
        } else if (se.getSwitch().equals(sw2)) {
            while (sw2.isClosed()) {
                starting_servo_position = starting_servo_position - delta - tolerance >= 0f ? starting_servo_position - delta : 0;
                servo1.setPosition(starting_servo_position);
                servo2.setPosition(starting_servo_position);
                //System.out.println("position = " + Float.toString(starting_servo_position));
                Utils.sleep(25);
            }
        }
    }

    /**
     * Fires when switch released. Unused.
     * @param se Switch event. Unused.
     */
    public void switchReleased(SwitchEvent se) {
    }

    /**
     * Called if the MIDlet is terminated by the system.
     * It is not called if MIDlet.notifyDestroyed() was called.
     *
     * @param unconditional If true the MIDlet must cleanup and release all resources.
     */
    protected void destroyApp(boolean unconditional) throws MIDletStateChangeException {
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

    /**
     * Gets the angle via compass module
     * @return Compass angle
     * @throws IOException if compass failure
     */
    private double getAngle() throws IOException {
        byte[] data = new byte[8];

        i2c.write(I2C_ADDRESS, CMD_MEASURE, 0, 2);
        i2c.read(I2C_ADDRESS, data, 0, 8);

        int upperAngle = 0xFF & data[1];
        int lowerAngle = 0xFF & data[2];
        double angle = (upperAngle << 8) + lowerAngle;
        upperAngle = 0xFF & data[3];
        lowerAngle = 0xFF & data[4];
        // unused
        //int declinationAngle = (upperAngle << 8) + lowerAngle;
        return angle/10;
    }

    /**
     * Returns a valid angle in [0 360]
     * @param x
     * @return Valid angle
     */
    private double checkAngle(double x) {
        if (x > 360) {
            return x % 360;
        }
        return x >= 0 ? x : x + 360;
    }

    /**
     * Returns the average angle
     * @return Angle
     * @throws IOException 
     */
    private double getAvgAngle() throws IOException {
        for (int i = 0; i < 2; i++) {
            getAngle();
        }
        double sum = 0;
        double min = Double.MAX_VALUE;
        for (int i = 0; i < NUM_AVG_READINGS; i++) {
            double angle = getAngle();
            sum += angle;
            min = Math.min(min, angle);
        }
        sum -= min;
        return sum / (NUM_AVG_READINGS - 1);
    }
    // corresponds to degrees -60 to 60
    private static final double[] asinLookup = new double[]{
        0, 0.017454178737585, 0.034913677698806, 0.052383831720078, 0.069870004975064, 
        0.087377605924218, 0.104912102606151, 0.122479038392806, 0.140084048337536, 
        0.157732876254456, 0.175431392679044, 0.193185613874249, 0.211001722063741, 
        0.228886087094804, 0.246845289758441, 0.264886147024236, 0.283015739483281, 
        0.301241441335296, 0.319570953307260, 0.338012338952325, 0.356574064851671, 
        0.375265045331157, 0.394094692412728, 0.413072971852013, 0.432210466274373, 
        0.451518446619238, 0.471008953346718, 0.490694889163938, 0.510590125408132, 
        0.530709624701538, 0.551069583099446, 0.571687595727842, 0.592582850906022, 
        0.613776359048718, 0.635291224347811, 0.657152969496191, 0.679389926750951, 
        0.702033712752794, 0.725119810184966, 0.748688287260934, 0.772784697234405, 
        0.797461216279661, 0.822778101828869, 0.848805589081666, 0.875626398156128, 
        0.903339110766513, 0.932062815889531, 0.961943660626584, 0.993164357510233, 
        1.025958462218693, 1.060632718251592, 1.097603850460327, 1.137463179066911, 
        1.181100087687805, 1.229967073304535, 1.286756209178897, 1.357717087474778, 
        1.469142265488490, 1.570796326794897, 1.570796326794897, 1.570796326794897};
    // +/- .03 radian accuracy

    /**
     * Uses lookup table for asin due to Java ME limitations
     * @param x
     * @return asin(x)
     * @throws IllegalArgumentException if result is imaginary
     */
    private double asin(double x) throws IllegalArgumentException {
        int n = (int) Math.floor(Math.toDegrees(x));
        
        if (n > 60 || n < -60) {
            throw new IllegalArgumentException("n not in interval [-60, 60]: " + n);
        }
        if (n >= 0) {
            return asinLookup[n];
        } else {
            return -asinLookup[-n];
        }
    }

    /**
     * Calculate theta3, unknown of side-angle-side triangle using law of
     * cosines and law of sines.
     * @param d1 Distance1
     * @param d2 Distance2
     * @param a1 Angle1
     * @param a2 Angle2
     * @return Calculated angle3
     */
    public double calcAngle3(double d1, double d2,double a1, double a2) {

        double diff = Math.abs(a1 - a2);
        if (diff > 180) 
            diff = 360 - diff;
        
        double a1d = a1;
        double a2d = a2;
        a1 = Math.toRadians(a1);
        a2 = Math.toRadians(a2);
        diff = Math.toRadians(diff);

        double d3 = Math.sqrt(d1*d1 + d2*d2 - (2*d1*d2*Math.cos(diff)));
        // diff -> d3
        // a3 -> d2
        double a3;
        if (d2 < d3) {
            a3 = asin(Math.sin(diff)*d2/d3); 
            a3 = Math.toDegrees(a3);
        }
        else {
            double temp = asin(Math.sin(diff)*d1/d3); 
            temp = Math.toDegrees(temp);
            a3 = 180 - (temp + diff);
        }
        
        System.out.println("a3: " + a3);
        //double ret = -a1d + a3;
        double ret = 180 - (360-a1d+a3);
        System.out.println("before checkangle: " + ret);
        //double ret = a1d + (180 - a3);
        return checkAngle(ret);
    }

    /**
     * Calculate theta3, unknown of side-angle-side triangle using law of
     * cosines and law of sines.
     * @param d1 Distance1
     * @param d2 Distance2
     * @param a1 Angle1
     * @param a2 Angle2
     * @return Calculated tilt3
     */
    public double calcTilt3(double d1, double d2, double a1, double a2)  {

        a1 *= 180;
        a2 *= 180;

        double diff = Math.abs(a1-a2);
        diff = Math.toRadians(diff);
        a1 = Math.toRadians(a1);
        a2 = Math.toRadians(a2);

        double d3 = Math.sqrt(d1 * d1 + d2 * d2 - (2 * d1 * d2 * Math.cos(diff)));
        double a4;
        if (d2 < d3) {
            a4 = asin(Math.sin(diff)*d2/d3); 
            a4 = Math.toDegrees(a4);
        }
        else {
            double temp = asin(Math.sin(diff)*d1/d3); 
            temp = Math.toDegrees(temp);
            a4 = 180 - Math.abs(temp + Math.toDegrees(diff));
        }
        
        a1 = Math.toDegrees(a1);
        double a3 = a4 - a1;
        a3 /= 180;
        System.out.println("a4: " + a4 + "\ta1: " + a1 + "a3: " + a3);
        
        return a3;
    }

    /**
     * Calculate optimal way to turn to new angle -- shortest distance
     * @param a2 angle1
     * @param a1 angle2
     * @return Optimal angle difference
     */
    private double angleDiff(double a2, double a1) {
        double d1 = 360 - a2 + a1;
        double d2 = a2 - a1;
        double d3 = a1 - a2;
        double d4 = 360 - a1 + a2;
        
        double min1;
        //System.out.println("d1: " + d1 + " d2 " + d2 + " a1: " + a1 + " a2: " + a2);
        if (Math.abs(d1) < Math.abs(d2)) {
            min1 = -d1;
        } else {
            min1 = d2;
        }
        
        double min2;
        if (Math.abs(d3) < Math.abs(d4)) {
            min2 = -d3;
        } else {
            min2 = d4;
        }
        if (Math.abs(min1) < Math.abs(min2))
            return min1;
        else
            return min2;
    }

    /**
     * Move the Mount to new angle
     * @param goal_angle New angle
     * @throws IOException if compass error
     */
    private void gotoAngle(double goal_angle) throws IOException {
        if (goal_angle < 0 || goal_angle > 360) 
            throw new Error("Bad angle: " + goal_angle);

        int iterations = 0;
        double currentAngle = getAvgAngle();
        double angle_diff = angleDiff(goal_angle, currentAngle);
        while (Math.abs(angle_diff) > ANGLE_TOLERANCE) {
            System.out.println("current angle: " + currentAngle + "\tDiff: "
                    + angle_diff + "\tpServo1: " + pServo1);
            if (iterations++ > MAX_NUM_ITERATIONS) {
                System.err.println("Tilt movement unsuccessful after "
                        + MAX_NUM_ITERATIONS + " iterations.");
                System.err.println("current tilt: " + currentAngle + "\tGoal: "
                        + goal_angle);
                break;
            }
            currentAngle = getAvgAngle();
            angle_diff = angleDiff(goal_angle, currentAngle);
            pServo1 += angle_diff % 180 / (360.0 * 2);
            servo1.setPosition(pServo1);

            if (Math.abs(pServo1) >= 1) {
                System.err.println("pServo1 value magnitude too high in angle set: " + pServo1);
                resetServos();
                break;
            }

            Utils.sleep(250);
        }
        System.out.println("Done w/ angle\ncurrent angle: " + currentAngle + "\tDiff: "
                + angle_diff + "\tpServo1: " + pServo1 + '\n');
    }

    /**
     * Move the mount to tilt
     * @param goal_tilt New tilt
     * @throws IOException 
     */
    public void gotoTilt(double goal_tilt) throws IOException {
        // increasing servo2 -> increasing tilt
        // using an upward tilt to mean:
        if (Math.abs(goal_tilt) > 1) {
            throw new Error("Bad tilt: " + goal_tilt);
        }

        int iterations = 0;
        double currentTilt = acc.getTiltY();
        double tilt_diff = goal_tilt - currentTilt;
        while (Math.abs(tilt_diff) > TILT_TOLERANCE) {
            System.out.println("current tilt: " + currentTilt + "\tDiff: "
                    + tilt_diff + "\tpServo2: " + pServo2);
            if (iterations++ > MAX_NUM_ITERATIONS) {
                System.err.println("Tilt movement unsuccessful after "
                        + MAX_NUM_ITERATIONS + " iterations.");
                System.err.println("current tilt: " + currentTilt + "\tGoal: "
                        + goal_tilt);
                break;
            }
            currentTilt = acc.getTiltY();
            tilt_diff = goal_tilt - currentTilt;
            pServo2 -= tilt_diff / 5.0;
            servo2.setPosition(pServo2);

            if (Math.abs(pServo2) >= 1) {
                System.err.println("pServo2 value magnitude too high in tilt set: " + pServo2);
                resetServos();
                break;
            }

            Utils.sleep(200);
        }
        System.out.println("Done w/ tilt\ncurrent tilt: " + currentTilt + "\tDiff: "
                + tilt_diff + "\tpServo2: " + pServo2);
    }

    /**
     * Directs the mount to a desire tilt and angle.
     * @param goal_tilt New tilt
     * @param goal_angle New angle
     * @throws IOException if communication failure between 
     *      either compass or distance meter
     */
    public void gotoLocation(double goal_tilt, double goal_angle) throws IOException {
        if (USE_COMPASS) {
            gotoAngle(goal_angle);
        }
        gotoTilt(goal_tilt);
    }

    /**
     * Reset servos to default position
     */
    public void resetServos() {
        System.out.println("Resetting servo positions.");
        flashAllLEDs(RED, 750);
        pServo1 = .5f;
        pServo2 = .5f;
        starting_servo_position = .5f;
        servo1.setPosition(pServo1);
        servo2.setPosition(pServo2);
    }
}

