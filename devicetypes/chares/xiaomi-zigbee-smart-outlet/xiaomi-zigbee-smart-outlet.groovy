/**
 *  Xiaomi Zigbee Smart Outlet - model ZNCZ02LM (CN/AU/NZ/AR)
 *  Device Handler for SmartThings
 *  Version 1.4 (Jan 2021) for new SmartThings App (2020)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 * --------------------------------------------------------------------------------------------------------------------------------------------------
 *
 *  Heavily modified on v1.4, originally based on based on bspranger's version 1.2 from Apr 2019
 *  which was based on original device handler by Lazcad / RaveTam
 *  bspranger's had updates and contributions to code by a4refillpad, bspranger, marcos-mvs, mike-debney, Tiago_Goncalves, and veeceeoh
 *
 *  Turning debug as a setting idea taken from veeceeoh's Xioami for Hubitat Temp/Humidity device handler
 *
 *  Understanding how to parse Xioami's hourly report partial code taken from veeceeoh's Xioami for Hubitat Temp/Humidity device handler
 *
 *  Understanding some of the values of Xioami's hourly report ugly hex string as big endian string value comes from deconz rest plugin github
 *
 * --------------------------------------------------------------------------------------------------------------------------------------------------
 *
 *  Update 1.1 (Jan 10, 2021): Fix for correct encoding data type
 *
 *  Update 1.2 (Jan 12, 2021):
 *       - Add health check capability
 *       - more and more comments and learnings
 *       - added data refresh poll setting
 *       - added automatic data poll refresh every X minutes just in case some zigbee message is not reported even if set by configureReporting for any reason
 *       - reorganized code structure so native/non-native methods are understood
 *
 *  Update 1.3 (Jan 15, 2021):
 *       - Cleaned up code
 *       - Removed method for custom on/off
 *       - Changed logic order, and added ability to parse natively via zigbee.getEvent
 *       - Added notes on how exactly Xiaomi does its Zigbee reports via a custom attribute ID which is 0xFF01 or 0xFF02, and how to understand them
 *       - Modified value type from decimal to number for temperature offset, as it was breaking the reading to 0 degrees (not fully tested)
 *
 *  Update 1.4 (Jan 22, 2021):
 *       - Enable/disable debug and trace as a setting of the device
 *       - Cleared up attributions in comments
 *       - Fixed missing import physicalgraph.zigbee.zcl.DataType to process DataType class
 *       - Fixed logic around scheduled refreshes as per engineering comments sent by @nayeliz (doesnt work yet, seems like a SmartThings bug)
 *       - Added the Xiaomi custom parsing for the reporting attribute FF01
 *       - Reports now properly happen via Xiaomi's custom report attribute (kWh, power usage, Temperature), even if runEveryXXMinutes() doesnt work.
 *       - Default for temperature offset is now 6 degrees, as its the 'sweet spot' I seem to found with ZNCZ02LM.
 *
 * --------------------------------------------------------------------------------------------------------------------------------------------------
 *
 *  Notes regarding how some Xiaomi's devices like the ZNCZ02LM behaves:
 *       - They use custom non ZCL standard attributes for things like power and energy consumption
 *       - Sometimes the reporting values come as a zigbee message from cluster 0000 attribute ID FF01 or FF02 as a concatenated value of all readings
 *       - In the ZNCZ02LM the energy consumption in Wh doesn't reduce itself it it gets to 0, it will stay at around 114 Wh as it considers 0.1 being the default state
 *
 *  Notes regarding how SmartThings behaves:
 *    - Due to the nature of 'events driven' zigbee reported values, if the values aren't returned as an event,
 *          the ST App vertical line graphs in the device view are ugly as there are gaps between times,
 *          but too many zigbee messages by defining 'null' in configureReporting will probably overload the network with messages.
 *    - A refresh() has to happen twice at least in the installed() phase to actually get the on/off state through zigbee.onOffRefresh() for some reason
 *    - Logs may appear as they happened twice, but is actually SmartThings servers showing the same log twice because of a sync issue.
 *    - only a checkInterval sendEvent is required in the installed() method to enroll a device into DeviceWatch
 *    - delayBetween does not send commands, so it needs to call a method that includes the commands in a list
 *    - "Health Check" capability is required for the checkInterval sendEvent configuration for deviceWatch
 *    - "Refresh" capability is required if you want to use the refresh() command to forcebly get zigbee attributes from a device
 *    - "Configuration" capability is required if you want to use settings in "edit settings" on the device in the App
 *    - while() loops behave HORRIBLY in SmartThings groovy, its better to have a method for them.
 *          example: a while() in a refresh will not work, but if you put that while() in a method (call it looper()), and refresh() contains looper(), it'll work.
 *    - the DataType class from the zigbee library is useless unless you do a "import physicalgraph.zigbee.zcl.DataType", this is not documented
 *    - SmartThings treats Hex as strings, so if you're trying to evaluate something as 0x21 you should as "21", it doesnt recognize a .toHexString() as a hex value
 *    - There's an undocumented method called resetEnergyMeter() I found by accident, might be worthy to be researched.
 *
 */

// TODO: add lastcheckin so that we can use it to calculate the time passed of an hour, and try to reset the Kwh value in 000C/0055 (resetEnergyMeter() might be useful).
// TODO: Research on the 'power failure memory', which might require a firmware update (need a xiaomi hub for that).

import physicalgraph.zigbee.zcl.DataType

metadata {
	definition (name: "Xiaomi Zigbee Smart Outlet", namespace: "chares", author: "chares", ocfDeviceType: "oic.d.smartplug", runLocally: true, executeCommandsLocally: true, genericHandler: "Zigbee") {
		// The ZNCZ02LM does have Power, Energy, and temperature. but the temperature sensor is unreliable, so it needs an offset. It also has refresh capabilities, but in a custom format.
		capability "Actuator"
		capability "Configuration"
		capability "Refresh"
		capability "Switch"
		capability "Temperature Measurement"
		capability "Sensor"
		capability "Power Meter"
		capability "Energy Meter"
		capability "Health Check"
	}

	// Fingerprint Profile for Xiaomi Zigbee smart outlet ZHA device
	fingerprint profileId: "0104", inClusters: "0000,0400,0003,0006", outClusters: "0019,000A", manufacturer: "LUMI", model: "lumi.plug", deviceJoinName: "Xiaomi Zigbee Smart Outlet"
	fingerprint profileId: "0104", inClusters: "0000,0400,0003,0006", outClusters: "0019,000A", manufacturer: "XIAOMI", model: "lumi.plug", deviceJoinName: "Xiaomi Zigbee Smart Outlet"

	// Removed tiles code as new ST App uses the capabilities and ocfDeviceType to define the tiles (custom capabilities must be added through the CLI)

	// simulator metadata
	simulator {
		// status messages
		status "on": "on/off: 1"
		status "off": "on/off: 0"
		// reply messages
		reply "zcl on-off on": "on/off: 1"
		reply "zcl on-off off": "on/off: 0"
	}

	// Define the data refresh rates list for the preferences setting input
	def rates = [:]
	rates << ["5" : "Refresh data every 5 minutes"]
	rates << ["10" : "Refresh data every 10 minutes"]
	rates << ["15" : "Refresh data every 15 minutes"]
	rates << ["30" : "Refresh data every 30 minutes"]

	preferences {
		// Temperature offset config. Set default at 6 as it is what i've seen is mostly for the ZNCZ02LM
		input "temperatureOffset", "decimal", title: "Set temperature offset", description: "Adjust temperature in X degrees (default: 6)", range:"*..*", defaultValue: 6

		// Allow the setting of the data poll refresh rate
		input name: "refreshRate", type: "enum", title: "Refresh time rate", description: "Change the data poll refresh rate (default: 10)", options: rates, required: false, defaultValue: "10"

		// Add a debugging option into the device settings
		input name: "debugLogging", type: "bool", title: "Enable debug logging", description: "Enables the debug logging to see in the IDE", defaultValue: false

		// Add a trace option into the device settings
		input name: "traceLogging", type: "bool", title: "Enable trace logging", description: "Enables the trace logging to see in the IDE", defaultValue: false
	}
}


/////////////////////////////////////////////////////////////////////////////////
// Native SmartThings groovy methods declaration

// Seems ths portion of code happens as soon as a device is installed in the hub
def installed() {
	//
	// The installed() method will call initialize(), this is part of how SmartThings works
	//
	// The following sendEvent should change the deviceWatch default check interval from 5m to 12m
	// the data field defines the protocol and hub ID
	// This 'should' execute the ping() which executes a refresh()
	//
	// NOTE: Even if it actually does enroll the device into deviceWatch, it seems that any message, regardless of being correctly parsed,
	// keeps the device 'online', thus ping almost never happens
	//
	sendEvent(name: "checkInterval", value: 720, displayed: false, data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID])
	//
	// Seen cases where the checkInterval should be in the configure stage, but others in the installed()
	// Also removed the offlinePingable: "1" from the data as according to @nayeliz is deprecated and ignored
}


// initialize mostly happens exactly after device installation, or the updated() method executes
//def initialize() {
	//
	// According to @nayeliz in ST forums, a DeviceWatch-Enroll is only needed in specific cases, the Health check capability should be enough

	// IF you need to do a specific enrollment on DeviceWatch it must happen on initialize, there isn't much documentation of this
	//sendEvent(name: "DeviceWatch-Enroll", value: JsonOutput.toJson([protocol: "zigbee", scheme:"untracked"]), displayed: false)

	// Not entirely known what EnrolledUTDH does, but seems needed by DeviceWatch
	//updateDataValue("EnrolledUTDH", "true")
//}


// updated() seems to happen for example after you save settings in the device (it also seems to happen after install)
def updated() {
	// 	The updated() method will call initialize(), this is part of how SmartThings works

	log.info "Updating settings of the device"

	// Unsechedule any schedule if there is any
	unschedule(scheduledRefresh)

	// runEveryXXMinutes() cannot call refresh(), as refresh() runs as a command when done manually, but not on a schedule,
	// thus requiring a separate method that specifically sends a hub command.

	// Also runEveryXXMinutes() must call a NON private method, and without the void ()

	// Define a list of scheduled refresh time options for the user to set in the settings page, set 10 minutes as default
	switch(refreshRate) {
		case "5":
			runEvery5Minutes(scheduledRefresh)
			log.info "Data poll refresh rate has been updated and is scheduled to run every 5 minutes"
			break
		case "15":
			runEvery15Minutes(scheduledRefresh)
			log.info "Data poll refresh rate has been updated and is scheduled to run every 15 minutes"
			break
		case "30":
			runEvery30Minutes(scheduledRefresh)
			log.info "Data poll refresh rate has been updated and is scheduled to run every 30 minutes"
			break
		default:
			runEvery10Minutes(scheduledRefresh)
			log.info "Data poll refresh rate has been updated and is scheduled to run every 10 minutes"
	}
}


// Add ping command for DeviceWatch to do a refresh in case device no longer sends any data
def ping() {
	// the custom scheduledRefresh() method will force a hub command, as the refresh() does not when not done manually
	scheduledRefresh()
}


// Off command section
def off() {
    // Enable debug to see the execution of the 'off' command
	displayTraceLog("Turning off the device")

	// Create the event in the system
	sendEvent(name: "switch", value: "off")

	// Send the command to the device
    zigbee.off()
}


// On command section
def on() {
    // Enable debug to see the execution of the 'on' command
	displayTraceLog("Turning on the device")

	// Create the event in the system
	sendEvent(name: "switch", value: "on")

	// Send the command to the device
    zigbee.on()
}


// Configure command section (runs at device add)
def configure() {
    // Log the configure stage
	log.info "Configuring Reporting and Bindings, setting schedule and forcing first report"

    // The Xiaomi Outlet ZNCZ02LM is not compliant with the ZCL standard, so simpleMeteringPowerConfig / simpleMeteringPowerRefresh / temperatureConfig cannot be used

    // The reporting config has to happen, as it is later used by the refresh() command while running the readAttribute() command
	// This basically sets how much time (min+max) and how much change has to happen for an event to trigger (example: usage went from 1Watts to 2Watts)

	// Ensure a delay of 500ms happens between zigbee commands as I've seen weird things if all are sent one after another
	delayBetween(zigbeeReportCommands(), 500)

	// Initial temperature reading of ZNCZ02LM for some reason sometimes is 12.5 or 17.5 degrees, it might be 'internal operating temperature'
}


// Refresh command section (runs at refresh requests)
def refresh() {
    // Log when a refresh command is executed
	log.info "Running refresh command and requesting refresh values from device"

	// As per @nayeliz mentioned, delayBetween doesn't actually execute commands itself, so needs to call a method that actually does them

	// Ensure a delay of 500ms happens between zigbee commands as I've seen weird things if all are sent one after another
	delayBetween(zigbeeRefreshCommands(), 500)
}


// This is an interesting one, its not documented yet it seems its the method to reset the energy meter counter
//resetEnergyMeter() {
	//This should happen when a runIn is executed maybe in the refresh() command, and clear up 000C/0055
//}


// Parse incoming device messages to generate events
def parse(String description) {
	// Enabling this debug will attempt to get all messages
    displayDebugLog("Incoming Zigbee description message: '${description}'")

	// try to automatically resolve the zigbee event received by the hub
	def zigbeeParsedEvent = zigbee.getEvent(description)

	// If the zigbee event is understood, send it as it is, if not then start the parsing
    if (zigbeeParsedEvent) {
		// Enabling this debug will attempt to get the zigbee parsed event
		displayDebugLog("Zigbee event extracted from description: ${zigbeeParsedEvent}")

		// Send event
        sendEvent(zigbeeParsedEvent)
    }
	else {
		// create a key/value map variable to use
		Map map = [:]

		// In case an event of read attribute is generated, which means its parsed as an understood zigbee message,
		// try to extract the values, otherwise process the catch all for unsupported events
		if (description?.startsWith('read attr -')) {
			// Execute the parse if it is understood as a traditional zigbee reported attribute
			map = parseReportedAttributeMessage(description)

			// Once the message is processed, verify if the map was created and create an event with it, otherwise dont do anything
			if (map) {
				// Enabling this debug will print the mapped message if ther is a map to be done
				displayDebugLog("The following mapped event is being created: ${map}")

				return createEvent(map)
			}
		}
		else if (description?.startsWith('catchall:')) {
			// It Seems that Xiaomi sends the reported values ALL in one through cluster 0000 attribute 0xFF01, and doesn't send it via traditional reporting attributes.

			// Attempt to parse the catchall message, for example the 0xFF01 one
			parseCatchAllMessage(description)
		}
		else {
			// Enable trace for seeing loggin messages that are not read attr or catchall
			displayTraceLog("Something was received by the hub, but wasn't able to be understood/parsed")

			displayDebugLog("The unknown message is: ${description}")
		}

    }
}


/////////////////////////////////////////////////////////////////////////////////
// Custom groovy functions and methods declaration


// Function to enable debug if setting is turned on
def displayDebugLog(message) {
	if (debugLogging) {
		log.debug "${device.displayName}: ${message}"
	}
}


// Function to enable trace if setting is turned on
def displayTraceLog(message) {
	if (traceLogging) {
		log.trace "${device.displayName}: ${message}"
	}
}


// Couple update and refresh in a method to be called by delayBetween()
def updateAndRefresh() {
	[
    	updated(),
		refresh()
    ]
}


// Group all the zigbee read attributes commands into one function/method, to be used by refresh() with a delay
def zigbeeRefreshCommands() {
	// Read the zigbee clusters on refresh, example clusters are:
    // cluster 0 is the basic cluster
    // cluster 1 attribute id 20 is battery (an outlet doesnt use battery, its mains powered)
    // cluster 2 attribute id 0 is the temperature (Non ZCL standard)
    // cluster 6 attribute id 0 is the on/off state, which can be obtained via the standard onOffRefresh()
    // cluster 0C, attribute 55 contains both the power (endpoint 02, in watts) and usage (endpoint 03 in kWh)
	[
		zigbee.onOffRefresh(), // Poll for the on/off state
		zigbee.readAttribute(0x0002, 0x0000), // Poll for the temperature in INT16
		zigbee.readAttribute(0x000C, 0x0055, [destEndpoint: 0x0002]), // Poll for the power usage in Watts (FLOAT4)
		zigbee.readAttribute(0x000C, 0x0055, [destEndpoint: 0x0003]) // Poll for the energy usage in Kwh (FLOAT4)
	]
}


// Group all the zigbee configur reporting commands into one function/method, to be used by a delayBetween()
def zigbeeReportCommands() {
	[
    	zigbee.onOffConfig(), // Poll for the on/off status
		zigbee.configureReporting(0x0002, 0x0000, 0x29, 1, 300, 0x01), // Set reporting time for temperature, which is INT16 (signed INT16)
		zigbee.configureReporting(0x000C, 0x0055, 0x39, 1, 300, 0x01, [destEndpoint: 0x0002]), // Set reporting time for power, which is in FLOAT4
		zigbee.configureReporting(0x000C, 0x0055, 0x39, 1, 300, 0x01, [destEndpoint: 0x0003]) // Set reporting time for energy usage, which is in FLOAT4
		//updateAndRefresh() // Ensure an update and refresh happens so that the schedule is set, and first report is received
	]
}


// Create a function specifically for scheduled refreshes
def scheduledRefresh() {
	log.info "Running scheduled refresh command and requesting refresh values from device"

	// As per @nayelyz mentioned, sendHubCommand is required when used outside of refresh(), since its a scheduled task
	sendHubCommand(zigbeeRefreshCommands(), 500)
}


// Function for when the catchall is detected
def Map parseCatchAllMessage(String description) {
    // Enable debug to see the parsed zigbee message
	def zigbeeParse = zigbee.parse(description)
    displayDebugLog("A catchall event was parsed as ${zigbeeParse}")

	// Enabling this debug will attempt to get the zigbee description message as a map
	def descParsedMap = zigbee.parseDescriptionAsMap(description)
	displayDebugLog("Parsed Zigbee description as a map: ${descParsedMap}")

	// Detect Xiaomi's report all state values in one attribute as data (0xFF01)
	if (descParsedMap.clusterId == "0000" && descParsedMap.attrId == "FF01") {
		// Even if the type is 0x42 (String characters), it's actually a Hex little endian concatenated value of all states like voltage+temp+power+energy+etc
		// It is not fully clear what each value represents, buth thanks to deconz and other places we got an idea

		// Enable debug to validate reception of message
		displayTraceLog("Received Xiaomi's device data report")

		// Execute the function to extract the values from Xiaomi's hourly report and generate events
		// Pass the data instead of the value of the map, as it already comes in little endian (no need to reverse it)
		parseCheckinMessage(descParsedMap.data)
	}
}


// Function for when a 'read attr' is detected
// The 'createEvent' module is what actually parsed the message into "temperature", "switch", "power", "energy"
def Map parseReportedAttributeMessage(String description) {

	Map descMap = (description - "read attr - ").split(",").inject([:]) {
		map, param ->
		def nameAndValue = param.split(":")
		map += [(nameAndValue[0].trim()):nameAndValue[1].trim()]
	}

	Map resultMap = [:]

	if (descMap.cluster == "0002" && descMap.attrId == "0000") {
		// Parse the temperature value
		int parsedTemperature = temperatureParse(descMap.value)

        // Removed the zigbee.parseHATemperatureValue as its not clear the reason why it exists

		// Generate the map to later send an event
		resultMap = createEvent(name: "temperature", value: parsedTemperature, unit: getTemperatureScale(), translatable: true)

        // Enable debug to see the parsed temperature zigbee message
		displayTraceLog("Temperature zigbee event reported as ${resultMap.value}° ${getTemperatureScale()}")
	}
	else if (descMap.cluster == "000C" && descMap.attrId == "0055" && descMap.endpoint == "02") {
        def wattage_int = Long.parseLong(descMap.value, 16)
		def wattage = Float.intBitsToFloat(wattage_int.intValue())

		wattage = Math.round(wattage * 10) * 0.1

		resultMap = createEvent(name: "power", value: wattage, unit: 'W')

        // Enable debug to see the parsed power usage zigbee message
		displayTraceLog("Power usage zigbee event reported as ${wattage} W")
	}
	else if (descMap.cluster == "000C" && descMap.attrId == "0055" && descMap.endpoint == "03") {
        def energy_int = Long.parseLong(descMap.value, 16)
		def energy = Float.intBitsToFloat(energy_int.intValue())

		energy = Math.round(energy * 100) * 0.0001

		resultMap = createEvent(name: "energy", value: energy, unit: 'kWh')

        // Enable debug to see the parsed energy consumption zigbee message
		displayTraceLog("Energy consumption zigbee event reported as ${energy} kWh")
	}
	return resultMap
}


// This function will parse the ugly hourly report message sent by Xiaomi's devices
def parseCheckinMessage(hexDataList) {
	// SmartThings is nice enough to give it to us already mapped, so there is no need to reverse from big to little endian and make a mess
	//
	// The sequence is as follows:
	// First 2 bytes are the attribute of the zigbee message in big endian (01 FF, which ix 0xFF01)
	// 3rd byte is the type, which is 42, char_string (it sends a string)
	// 4th byte some say its the length in bytes, yet it is not. not clear what it is.
	// from the 5th, the actual data follows this pattern:
	//		- first byte is the 'tag', only Xiaomi knows what they are, because of the community we know some of them (voltage, temperature, power, etc.)
	//		- second byte is the DataType, for example 0x039, which is float4, meaning it has a length of 4 bytes
	//		- based on the second byte that tells you the length, the following bytes in that length are the data, using the previous example, its the 4 next bytes.
	//		- pattern starts again

	// Get the list (which is an Array list, that's why the -1) length to define boundaries
	int dataListSize = hexDataList.size()

	// Define the starting item as the 4th, so that the next ++ starts on the first tag
	int startingListPosition = 4

	// Start cycling through the pattern to extract the data
	while (startingListPosition < dataListSize) {

		// This should obtain the hex string object (remember hex values are in string java object type)
		String xiaomiTag = hexDataList.get(startingListPosition)
		startingListPosition++

		// Extract the Zigbee DataType value to get the length of the following data
		String dataType = hexDataList.get(startingListPosition)
		startingListPosition++

		// With the DataType value, convert it first to integer to get the length from the DataType.getLength() method using radix 16 (hex value)
		def dataLength = DataType.getLength(Integer.parseInt(dataType, 16))

		// There should be a check here for an unknown DataType, but the problem with that is you dont know the length of it, which brings issues
		// Until someone reports that for X reason Xiaomi sends an unknown zigbee DataType, there is no point in doing the logic for this check.

		// Once the length is known, the next following bytes are the data to extract
		int dataPosition = 1

		// Define the dataPayload as string and clear the value
		String dataPayload

		// Attempt to cicle and add the data to a string to later convert until it reaches the length defined
		while (dataPosition <= dataLength) {
			// Start with the first item, and then cicle in the while following the dataType previously taken
			String dataPiece = hexDataList.get(startingListPosition)

			// Ensure the first item in datapayload isnt summed up to a null value
            if (dataPosition == 1) {
            	dataPayload = dataPiece
            }
            else {
				// This is to ensure that when a float (datatype 39) is detected, the endianess is reversed
				if (dataLength > 1 && dataType == "39") {
					dataPayload = dataPiece + dataPayload
				}
				else {
					dataPayload = dataPayload + dataPiece
				}
			}

 			startingListPosition++
            dataPosition++
		}

		displayDebugLog("The Xiaomi tag parsed was: ${xiaomiTag}, the type was ${dataType}, the length was ${dataLength}, the data was ${dataPayload}")

		// Now that we have the payload, attempt to parse it, and generate the events in the SmartThings system
		executeXiaomiParsing(xiaomiTag, dataType, dataPayload)
	}
}


// This is for parsing the custom Xiaomi messages
def executeXiaomiParsing(incomingDataTag, incomingDataType, incomingPayload) {
	// Depending on the Xiaomi device, this tags can change, the deconz rest api github, file de_web_plugin.cpp is a great source of info, as zigbee2mqtt github fromZigbee.
	// An example is tag 0x64, in the ZNCZ02LM is on/off, others is actually temperature.

	// If the tag is 0x01 or 0x02 and is a 16 bit uint, its battery
	// If the tag is 0x03 and is a 8 bit int, its temperature in °C
    // If the tag is 0x04, its unknown
	// If the tag is 0x05 is rssi
	// If the tag is 0x06 is LQI
    // If the tag is 0x07 or 0x08 or 0x09, they are unknown
	// If the tag is 0x0A is the parent zigbee DNI
	// If the tag is 0x0B, and the device is a light sensor, its light level, otherwise its unknown
	// If the tag is 0x64 and is a boolean, its on/off
	// If the tag is 0x64 and is something else, its most likely temperature (can also be smoke/gas detection, or lift)
	// If the tag is 0x65 and is a 16 bit int, its humidity (can also be on/off or unknown)
	// If the tag is 0x66 and is a 32 bit int, its pressure (can also be unknown)
	// If the tag is 0x95 and is a single float, its consumption in Watts / Hour (must do round(f * 1000) )
	// If the tag is 0x96 and is a single float, its voltage  (must do round(f / 10) ) (can also be unknown)
	// If the tag is 0x97 and is a single float, its current in mA  (can also be unknown)
	// If the tag is 0x98 and is a single float, its power in Watts

	// Evaluate the data tag received and parse it if possible
	// Because SmartThings treats Hex as strings, evaluation must be done as literal strings
	switch (incomingDataTag) {
		case "03":
			// Parse the temperature accordingly
			int parsedTemperature = temperatureParse(incomingPayload)

			// Log to debug if enabled
			displayTraceLog("Temperature in scale ${getTemperatureScale()} is ${parsedTemperature}")

			// supposedly createEvent is uses in the parse() section (where we are at here), sendEvent creates AND fires the event if you are outside the parse()
			sendEvent(name: "temperature", value: parsedTemperature, unit: getTemperatureScale(), translatable: true)

			break
		case "64":
			// If its on/off
			if (incomingDataType == "10") {
				// Seems since the value is already 00 or 01, parseInt doesnt like it
				//int intOnOff = Integer.parseInt(incomingPayload, 16)

				// Declare the variable before using it
				String onOffState

				// Make sure the payload is either 1 or 0 before sending an event

				if (incomingPayload == "01") {
					onOffState = "on"

					sendEvent(name: "switch", value: onOffState)
				}

				if (incomingPayload == "00") {
					onOffState = "off"

					sendEvent(name: "switch", value: onOffState)
				}

				displayTraceLog("On/Off value state is ${onOffState}")
			}
			// If its temperature, but this shouldnt happen in a Xiaomi Outlet
			else {
				// Parse the temperature accordingly
				int parsedTemperature = temperatureParse(incomingPayload)

				// Get the temperature scale from the hub system
				String temperatureScale = getTemperatureScale()

				// Log to debug if enabled
				displayTraceLog("Temperature in scale ${temperatureScale} is ${parsedTemperature}")

				// supposedly createEvent is uses in the parse() section (where we are at here), sendEvent creates AND fires the event if you are outside the parse()
				sendEvent(name: "temperature", value: parsedTemperature, unit: temperatureScale, translatable: true)
			}
			break
		case "95":
			// Energy consumption in Wh value has endianness reversed, its a float number

			// Use long parsing as it has more than one byte
			Long parsedEnergy = Long.parseLong(incomingPayload, 16);

			// Convert to float
        	Float floatEnergyValue = Float.intBitsToFloat(parsedEnergy.intValue());

			// Returned value is in Wh, but SmartThings expect kWh
			Float reducedFloatEnergyValue = floatEnergyValue / 100

            // Round to value to 4 decimal places
            Float roundFloatEnergyValue = Math.round(reducedFloatEnergyValue * 10000) / 10000

			// Log to debug if enabled
			displayTraceLog("Energy consumption in Wh is ${roundFloatEnergyValue}")

			// supposedly createEvent is uses in the parse() section (where we are at here), sendEvent creates AND fires the event if you are outside the parse()
			sendEvent(name: "energy", value: roundFloatEnergyValue, unit: 'kWh')

			break
		case "96":
			// Voltage in V, not implemented as havent seen it reported in ZNCZ02LM
			int voltageParsed = Integer.parseInt(incomingPayload, 16)

			// Log to debug if enabled
			displayTraceLog("Voltage in V is ${voltageParsed}")

			break
		case "97":
			// Current in mA, not implemented as havent seen it reported in ZNCZ02LM
			int currentParsed = Integer.parseInt(incomingPayload, 16)

			// Log to debug if enabled
			displayTraceLog("Current in mA is ${currentParsed}")

			break
		case "98":
			//def reversePowerHex = Integer.reverseBytes(incomingPayload)

			// Use long parsing as it has more than one byte
			Long parsedPower = Long.parseLong(incomingPayload, 16);

			// Convert to float
        	Float floatPowerValue = Float.intBitsToFloat(parsedPower.intValue());

			// Log to debug if enabled
			displayTraceLog("Power in Watts is ${floatPowerValue}")

			// supposedly createEvent is uses in the parse() section (where we are at here), sendEvent creates AND fires the event if you are outside the parse()
			sendEvent(name: "power", value: floatPowerValue, unit: 'W')

			break
		default:
			displayDebugLog("Unknown data tag ${incomingDataTag} with type ${incomingDataType} has been processed")
	}
}


// Function to parse Xiaomi's temperature value from the custom FF01 report
def temperatureParse(inputTemperature) {
	// Temperature in Celcius is always received too high in Xiaomi outlets, must divide by 2, which is in line from what is seen on other Xiaomi integrations
	// Also add on account the offset (the last part is for when it hasnt been setup, if temperatureOffset doenst exist yet, set it as 6, which is the default in input)

	int dividedTemp = Integer.parseInt(inputTemperature, 16)

	int temperature

    if (temperatureOffset == null) {
		temperature = dividedTemp/2 + 6
	}
    else {
    	temperature = dividedTemp/2 + temperatureOffset
    }

	String temperatureScale = getTemperatureScale()

	// Convert to Fahrenheit in case the scale is different
	if (temperatureScale == "F") {
		temperature = ((temperature * 1.8) + 32)
	}
	// There's not a clear indication why zigbee.parseHATemperatureValue might be needed

	displayDebugLog("Parsed temperature value is ${temperature}")

	return temperature
}


// The following code should work later on for handling the power memory setting of ZNCZ02LM which is different to other Xiaomi outlets
// My guess is that attribute 0xFFF0 is added by a firmware update (waiting for a Xiaomi Gateway gen3 to test this)
//
// The cluster is the basicConfig one (0x000), Attribute ID is 0xFFF0, and type is 0x41 (Octet)
//
// I know the octet values is one of these, but not sure yet which or all, or how exactly it expects them:
//    [[0xaa, 0x80, 0x05, 0xd1, 0x47, 0x07, 0x01, 0x10, 0x01], [0xaa, 0x80, 0x03, 0xd3, 0x07, 0x08, 0x01]] :
//    [[0xaa, 0x80, 0x05, 0xd1, 0x47, 0x09, 0x01, 0x10, 0x00], [0xaa, 0x80, 0x03, 0xd3, 0x07, 0x0a, 0x01]];
//
// And it seems that some ZNCZ02LM either don't support it or they require a firmware update
//
// Also not yet sure how exactly does "st wattr" expect an octet value
//
//def set_power_memory() {
//
//	//zigbee.writeAttribute() still doesnt support 0x41 (Octet) type
//	//
//  // Example of writeAttribute:
//	//zigbee.writeAttribute(0x0000, 0xFFF0, 0x41, value1, [mfgCode:0x115F])
//	//zigbee.writeAttribute(0x0000, 0xFFF0, 0x41, value2, [mfgCode:0x115F])
//
//  // Old "st wattr" method is required due to the previous comment to write the value
//	"st wattr 0x${device.deviceNetworkId} 1 0 0xFFF0 0x41 {0xaa, 0x10, 0x05, 0x41, 0x47, 0x01, 0x01, 0x10, 0x01}"
//}