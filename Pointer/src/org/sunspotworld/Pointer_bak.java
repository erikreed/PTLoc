/*
 * Pointer.java
 *
 * Created on Oct 4, 2011 2:06:44 PM;
 */

package org.sunspotworld;

import com.sun.spot.peripheral.radio.RadioFactory;
import com.sun.spot.resources.Resources;
import com.sun.spot.resources.transducers.ISwitch;
import com.sun.spot.resources.transducers.ITriColorLED;
import com.sun.spot.resources.transducers.ITriColorLEDArray;
import com.sun.spot.sensorboard.IDemoBoard;
import com.sun.spot.sensorboard.peripheral.Accelerometer3D;
import com.sun.spot.service.BootloaderListenerService;
import com.sun.spot.util.IEEEAddress;
import com.sun.spot.util.Utils;

import javax.microedition.midlet.MIDlet;
import javax.microedition.midlet.MIDletStateChangeException;

/**
 * The startApp method of this class is called by the VM to start the
 * application.
 * 
 * The manifest specifies this class as MIDlet-1, which means it will
 * be selected for execution.
 */
public class Pointer_bak extends MIDlet {

    private ITriColorLEDArray leds = (ITriColorLEDArray) Resources.lookup(ITriColorLEDArray.class);

    protected void startApp() throws MIDletStateChangeException {
        System.out.println("Hello, world");
        BootloaderListenerService.getInstance().start();   // monitor the USB (if connected) and recognize commands from host

        long ourAddr = RadioFactory.getRadioPolicyManager().getIEEEAddress();
        System.out.println("Our radio address = " + IEEEAddress.toDottedHex(ourAddr));

        
        IDemoBoard board = (IDemoBoard)Resources.lookup(IDemoBoard.class);
        //board.getOutputPins()[0].setHigh();
        board.getOutputPins()[1].setHigh();
        board.getOutputPins()[2].setHigh();
        //board.getOutputPins()[3].setHigh();
        Utils.sleep(10000);
        //board.getOutputPins()[0].setLow();
        board.getOutputPins()[1].setLow();
        board.getOutputPins()[2].setLow();
        //board.getOutputPins()[3].setLow();
        ISwitch sw1 = (ISwitch) Resources.lookup(ISwitch.class, "SW1");
        ITriColorLED led = leds.getLED(0);
        led.setRGB(0,0,255); 
        int i = 0;
        while (i++ <= 5) {                  // done when switch is pressed
            led.setOn();                        // Blink LED
            Utils.sleep(250);                   // wait 1/4 seconds
            led.setOff();
            Utils.sleep(1000);                  // wait 1 second
        }
        notifyDestroyed();                      // cause the MIDlet to exit
    }

    protected void pauseApp() {
        // This is not currently called by the Squawk VM
    }

    /**
     * Called if the MIDlet is terminated by the system.
     * It is not called if MIDlet.notifyDestroyed() was called.
     *
     * @param unconditional If true the MIDlet must cleanup and release all resources.
     */
    protected void destroyApp(boolean unconditional) throws MIDletStateChangeException {
    }
}
