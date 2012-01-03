PTLoc -- Point to Location Device
====================
## Parts
* (2x) Java Sunspots
* SainIC Arduino Ultrasonic Module HC-SR04 Distance Sensor
* (2x) GY-26 Digital Compass Sensor Module
* HS-81MG Servo
* HS-311 Servo

## Overview

The Pointer collects and transmits data necessary for the Mount to determine the location to pivot to in its reference frame. The pointer was fixed with a SainIC Arduino Ultrasonic Module HC-SR04 Distance Sensor, which acquires distances to objects within a 10 meter range using emitted infrared signals. Additionally, a GY-26 Digital Compass Sensor Module was attached to collect the angle coordinates used in the Mount.s calculations. These two sensors used the Sunspot's 5 volt power supply, allowing the Pointer to be mobile. Both the Mount and Pointer employed the accelerometer functionality of the Sunspots, allowing the tilts along each axis of the devices to be captured without an external sensor.

The Mount was also fixed with a compass module, which was used to determine its current angle and for calculating how to move to a desired angle. To allow for two degrees of freedom, panning and tilting, two servos were attached, along with a base platform to hold the devices in place. The HS-81MG is larger than the HS-311 and was used for panning, while the HS-311 was used for tilting. The HS-81MG held the HS-311, Sunspot, and compass module in place.

## Pointer
The Pointer sends data to the Mount via  IEEE 802.15.4 transmission when a switch on the Sunspot is pressed. This data consists of three 64-bit floating point numbers and an integer; the three floating points contain the angle, tilt, and distance sensor readings, while the integer specifies the command intended by the pointer. We specify two commands: the first notifies the Mount of initial angle, tilt, and distance values without any computation, while the second triggers the Mount to perform the new angle computation based on the parameters of the first command and move pivot to a new angle. This allowed for flexibility in future implementations; the distance to the Mount can be synchronized by using the first command, and assuming the Pointer's location is stationary, the second command could direct the Mount to new positions without requiring two distance and two angle measurements.
    
The two commands were sent to the Mount depending on the Sunspot switch pressed, triggered via Java event handlers. To retrieve angle measurements from the GY-26 Compass Module, a packet was sent to the compass using the Sunspot's Inter-Integrated Circuit (I2C) interface. The response packet contained 8 bytes, 2 of which contained an integer corresponding to the compass angle times 10, a value between 0 and 3600. Both the Pointer and Mount used the average of 5 compass readings before performing a transmission or any calculations. Additionally, the compasses were calibrated before use by sending a calibration packet to each compass and slowly rotating for approximately two minutes. 
    
In retrieving the distance, the Sunspot demoboard was used to push current through IO pins D0 and D1, triggering a response from the SainIC Ultrasonic Distance Module. A pulse was sent through D1, causing the distance module to emit and echo. The time elapsed in microseconds until the echo is received was returned via IO pin 0. Dividing this value by 148 yields the distance traveled one way in inches. As with the compass module, distance measurements were averaged over 5 sensor readings before any transmissions or calculations.
    

## Mount   
The Mount controls two servos using an additional 4V power supply and the Sunspot's high current H0/H1 pins. The servos are controlled using a Sunspot Servo library, allowing a value between 0 and 1 to be used to direct the servos to a position within their operating range. When powered on, the Mount acts idle, listening every .25 seconds for a transmission from the Pointer. 
If the command to turn to a location is received, the formulas described in Methods are used to calculate the new angle for the Mount. This angle will be referred to as the goal angle. Additionally, a arcsin lookup table is used in the computations due to restrictions of the Java ME math libraries.

    The two servos, controlling tilt and horizontal movement, are controlled separately and use separate angle values. The tilt calculations use the Sunspot accelerometer tilt values as angles, while the horizontal movement uses compass sensor readings. To direct the servo to navigate to the goal angle, the difference angle between the current angle and the goal angle is calculated. This angle has multiple correct values, so the smallest is calculated. With the angle difference obtained, the servo begins moving in the direction of the goal angle in several iterations, halting when it current compass readings are within a tolerance of 1 degree from the goal angle. 


## How to Use
To use the Point to Location Framework, a directional device was fixed to the Mount. Provided there is line of sight to the Mount and the range of the distance meter are not exceeded, the user can point the Pointer at the Mount and press switch 1. This causes a visible notification on the Pointer that the sensor readings were successfully retrieved, as well as on the Mount to notify that the transmission was received. Pointing the Pointer to another location and pressing switch 2 commands the Mount to perform angle calculations and turn to face the location specified by the Pointer. There will be a visual notification of success from both the Mount and Pointer, or a flashing red light indicating an error from the Mount. This implies the servo motors are dysfunctional, or the Mount is attempting to turn to a location outside of its servo motor range.
