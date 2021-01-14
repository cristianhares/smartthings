/**
 *  Xiaomi Zigbee Smart Outlet - model ZNCZ02LM (CN/AU/NZ/AR)
 *  Device Handler for SmartThings
 *  Version 1.3 (Jan 2021) for new SmartThings App (2020)
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
 *  Based on original device handler by Lazcad / RaveTam
 *  Updates and contributions to code by a4refillpad, bspranger, marcos-mvs, mike-debney, Tiago_Goncalves, and veeceeoh
 *  Modified based on bspranger's version 1.2 from Apr 2019
 *
 *  Update 1.1 (Jan 10, 2021): Fix for correct encoding data type
 *  Update 1.2 (Jan 12, 2021):
 *       - Add health check capability
 *       - more and more comments and learnings
 *       - added data refresh poll setting
 *       - added automatic data poll refresh every X minutes just in case some zigbee message is not reported even if set by configureReporting for any reason
 *       - reorganized code structure so native/non-native methods are understood
 *  Update 1.3 (Jan 15, 2021):
 *       - Cleaned up code
 *       - Removed method for custom on/off
 *       - Changed logic order, and added ability to parse natively via zigbee.getEvent
 *       - Added notes on how exactly Xiaomi does its Zigbee reports via a custom attribute ID which is 0xFF01 or 0xFF02, and how to understand them
 *       - Modified value type from decimal to number for temperature offset, as it was breaking the reading to 0 degrees (not fully tested)
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
 *    - Seems for some reason the refresh() commands is called twice by the runEveryXMinutes(), maybe a ping() also happens?
 *    - A refresh() has to happen twice at least in the installed() phase to actually get the on/off state through zigbee.onOffRefresh() for some reason
 *    - only a checkInterval sendEvent is required in the installed() method to enroll a device into DeviceWatch
 *    - delayBetween seems to be a better choice for handling multiple zigbee commands instead of making a list variable and then doing "return commandlistvar"
 *    - "Health Check" capability is required for the checkInterval sendEvent configuration for deviceWatch
 *    - "Refresh" capability is required if you want to use the refresh() command to forcebly get zigbee attributes from a device
 *    - "Configuration" capability is required if you want to use settings in "edit settings" on the device in the App
 *
 */

metadata {
	definition (name: "Xiaomi Zigbee Smart Outlet", namespace: "chares", author: "chares", ocfDeviceType: "oic.d.smartplug", runLocally: true, executeCommandsLocally: true, genericHandler: "Zigbee") {
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
		// Temperature offset config
		input "tempOffset", "number", title:"Set temperature offset", description:"Adjust temperature in X degrees", range:"*..*"

		// Allow the setting of the data poll refresh rate
		input name: "refreshRate", type: "enum", title: "Refresh time rate", description: "Change the data poll refresh rate (default: 10)", options: rates, required: false, defaultValue: "10"
	}
}


/////////////////////////////////////////////////////////////////////////////////
// Native SmartThings groovy methods declaration

// Seems ths portion of code happens as soon as a device is installed in the hub
def installed() {
	//initialize()
	//
	// This should change the deviceWatch default check interval from 5m to 12m
	// the data field defines the protocol and hub ID
	// This should execute the ping() which executes a refresh()
	// NOTE: Even if it actually does enroll the device into deviceWatch, it seems that any message, regardless of being correctly parsed, keeps the device 'online', thus ping almost never happens
	sendEvent(name: "checkInterval", value: 720, displayed: false, data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID])
	//
	// Seen cases where the checkInterval should be in the configure stage, but others in the installed()
	// Also removed the offlinePingable: "1" from the data as according to @nayeliz is deprecated and ignored
	//
	// Replaced the runIn in the update() method, and just add it here for when the installation first happens, and then the runEveryXMinutes() executes it
	delayBetween([
    	update(),
		refresh()
	], 2000)
}


// initialize mostly happens exactly after device installation, or the updated() method executes
//def initialize() {
	// According to @nayeliz in ST forums, a DeviceWatch-Enroll is only needed in specific cases, the Health check capability should be enough
	// An enrollment on DeviceWatch must happen on initialize, there isnt much documentation of this
	//sendEvent(name: "DeviceWatch-Enroll", value: JsonOutput.toJson([protocol: "zigbee", scheme:"untracked"]), displayed: false)
	// Not entirely known what EnrolledUTDH does, but seems needed by DeviceWatch
	//updateDataValue("EnrolledUTDH", "true")
//}


// updated() seems to happen for example after you save settings in the device
def updated() {
	// This will call the update() method after 2 seconds of detecting an updated preferences/settings
	runIn(2, update)
}


// Add ping command for DeviceWatch to do a refresh in case device no longer sends any data
def ping() {
	refresh()
}


// Off command section
def off() {
    // Enable debug to see the execution of the 'off' command
	//log.debug "Turning off the outlet"
	sendEvent(name: "switch", value: "off")
    zigbee.off()
}


// On command section
def on() {
    // Enable debug to see the execution of the 'on' command
	//log.debug "Turning on the outlet"
	sendEvent(name: "switch", value: "on")
    zigbee.on()
}


// Configure command section (runs at device add)
def configure() {
    // Enable debug for reporting when the configure command executed
	log.info "Configuring Reporting and Bindings"

    // The Xiaomi Outlet ZNCZ02LM is not compliant with the ZCL standard, so simpleMeteringPowerConfig / simpleMeteringPowerRefresh / temperatureConfig cannot be used

    // The reporting config has to happen, as it is later used by the refresh() command while running the readAttribute() command
	// This basically sets how much time (min+max) and how much change has to happen for an event to trigger (example: usage went from 1Watts to 2Watts)
	//
	// Ensure a delay of 500ms happens between zigbee commands as I've seen weird things if all are sent one after another
	delayBetween([
    	zigbee.onOffConfig(), // Poll for the on/off status
		zigbee.configureReporting(0x0002, 0x0000, 0x29, 1, 300, 0x01), // Set reporting time for temperature, which is INT16 (signed INT16)
		zigbee.configureReporting(0x000C, 0x0055, 0x39, 1, 300, 0x01, [destEndpoint: 0x0002]), // Set reporting time for power, which is in FLOAT4
		zigbee.configureReporting(0x000C, 0x0055, 0x39, 1, 300, 0x01, [destEndpoint: 0x0003]), // Set reporting time for energy usage, which is in FLOAT4
		refresh() // Ensure a last refresh happens after setting reporting, as sometimes refresh happens before the configure() sta
	], 500)

	// Initial temperature reading of ZNCZ02LM for some reason is 12.5 or 17.5 degrees, it might be 'internal operating temperature'
}


// Refresh command section (runs at refresh requests)
def refresh() {
    // Enable debug to see the execution of the 'refresh' command
	log.info "Running refresh command and requesting refresh values from device"

    // Read the zigbee clusters on refresh, the clusters are:
    // cluster 0 is the basic cluster
    // cluster 1 attribute id 20 is battery (an outlet doesnt use battery, its mains powered)
    // cluster 2 attribute id 0 is the temperature (Non ZCL standard)
    // cluster 6 attribute id 0 is the on/off state, which can be obtained via the standard onOffRefresh()
    // cluster 0C, attribute 55 contains both the power (endpoint 02, in watts) and usage (endpoint 03 in kWh)

	// Ensure a delay of 500ms happens between zigbee commands as I've seen weird things if all are sent one after another
	delayBetween([
		zigbee.onOffRefresh(), // Poll for the on/off state
		zigbee.readAttribute(0x0002, 0x0000), // Poll for the temperature in INT16
		zigbee.readAttribute(0x000C, 0x0055, [destEndpoint: 0x0002]), // Poll for the power usage in Watts (FLOAT4)
		zigbee.readAttribute(0x000C, 0x0055, [destEndpoint: 0x0003]) // Poll for the energy usage in Kwh (FLOAT4)
	], 500)

}


// This method will run when a setting has been updated via the updated() method
def update() {
	log.info "Updating the preference settings of the device"
	// Unsechedule any schedule if there is any
	unschedule()
	//
	//SmarThings documentation is lacing details as it doesn't specify that runEveryXMinutes() cannot be executed as a method
	//
	// Define a list of options for the user to set in the settings page
	switch(refreshRate) {
		case "5":
			runEvery5Minutes(refresh)
			log.info "Data poll refresh rate is scheduled to run every 5 minutes"
			break
		case "15":
			runEvery15Minutes(refresh)
			log.info "Data poll refresh rate is scheduled to run every 15 minutes"
			break
		case "30":
			runEvery30Minutes(refresh)
			log.info "Data poll refresh rate is scheduled to run every 30 minutes"
			break
		default:
			runEvery10Minutes(refresh)
			log.info "Data poll refresh rate is scheduled to run every 10 minutes"
	}
	// This will execute a refresh after 5 seconds as soon as the update happens, which also happens on install
	//runIn(5, refresh)
}


// Parse incoming device messages to generate events
def parse(String description) {
	// Enabling this debug will attempt to get all messages
    //log.debug "Incoming Zigbee parsed message: '${description}'"

	// try to automatically resolve the zigbee event received by the hub
	def result = zigbee.getEvent(description)

	// If the zigbee event is understood, send it as it is, if not then start the manual parsing
    if (result) {
		// Enabling this debug will attempt to get the zigbee parsed event
		//log.debug "Parsed Zigbee event natively: ${result}"

        sendEvent(result)
    }
	else {
		Map map = [:]

		// It Seems that Xiaomi sends the reported values ALL in one through cluster 0000 attribute 0xFF01, and doesn't send it via traditional reporting attributes.

		// In case an event of read attribute is generated, try to parse it, otherwise process the catch all for unsupported events
		if (description?.startsWith('read attr -')) {
			map = parseReportedAttributeMessage(description)
		}
		else if (description?.startsWith('catchall:')) {
			map = parseCatchAllMessage(description)
		}
		else {
			//Enable for seeing messages that are not read attr or catchall
			log.debug "Something was received by the hub, but wasn't able to be understood/parsed"
		}

		// Once the message is processed, verify if the map was created and create an event with it, otherwise dont do anything
		if (map) {
			// Enabling this debug will print the mapped message if ther is a map to be done
			//log.debug "The following mapped event is being created: ${map}"

			return createEvent(map)
		}
    }



}


/////////////////////////////////////////////////////////////////////////////////
// Custom groovy methods declaration

// This is used to convert the Hex returned from temperature
private Integer convertHexToInt(hex) {
	Integer.parseInt(hex,16)
}


// Function for when the catchall is detected
private Map parseCatchAllMessage(String description) {
	Map resultMap = [:]

    // Enable debug to see the parsed zigbee message
    //log.debug "A catchall event was parsed as ${zigbeeParse}"
	//def zigbeeParse = zigbee.parse(description)

	def descMap = zigbee.parseDescriptionAsMap(description)
	// Enabling this debug will attempt to get the zigbee description message as a map
	//log.debug "Parsed Zigbee description as a map: ${descMap}"

	// Detect Xiaomi's report all state values in one attribute as data (0xFF01)
	// Even if the type is 0x42 (String characters), it's actually a Hex concatenated value of all states like voltage+temp+power+energy+etc
	// It is not fully clear what each value represents
	if (descMap.cluster == "0000" && descMap.attrId == "FF01") {

			// For now, log the receipt of the Xiaomi's device data report
			log.info "Received Xiaomi's device data report"

			// Need to extract the data value, and extract each tag from it
			//
			// If the tag is 0x01 and is a 16 bit uint, its battery
			// If the tag is 0x03 and is a 8 bit int, its temperature in °C
			// If the tag is 0x64 and is a boolean, its on/off
			// If the tag is 0x64 and is a 16 bit int, its temperature
			// If the tag is 0x65 and is a 16 bit int, its humidity
			// If the tag is 0x66 and is a 32 bit int, its pressure
			// If the tag is 0x95 and is a single float, its consumption in Watts / Hour (must do round(f * 1000) )
			// If the tag is 0x96 and is a single float, its voltage  (must do round(f / 10) )
			// If the tag is 0x97 and is a single float, its current in mA
			// If the tag is 0x98 and is a single float, its power in Watts
			// Source: deconz rest plugin github

	}

}


// Function for when a 'read attr' is detected
// The 'createEvent' module is what actually parsed the message into "temperature", "switch", "power", "energy"
private Map parseReportedAttributeMessage(String description) {

	Map descMap = (description - "read attr - ").split(",").inject([:]) {
		map, param ->
		def nameAndValue = param.split(":")
		map += [(nameAndValue[0].trim()):nameAndValue[1].trim()]
	}

	Map resultMap = [:]

	if (descMap.cluster == "0002" && descMap.attrId == "0000") {
        def tempScale = getTemperatureScale()
        def tempValue = zigbee.parseHATemperatureValue("temperature: " + (convertHexToInt(descMap.value) / 2), "temperature: ", tempScale) + (tempOffset ? tempOffset : 0)

		resultMap = createEvent(name: "temperature", value: tempValue, unit: tempScale, translatable: true)

        // Enable debug to see the parsed temperature zigbee message
		//log.debug "Temperature zigbee event reported as ${resultMap.value}° $tempScale"
	}
	else if (descMap.cluster == "000C" && descMap.attrId == "0055" && descMap.endpoint == "02") {
        def wattage_int = Long.parseLong(descMap.value, 16)
		def wattage = Float.intBitsToFloat(wattage_int.intValue())

		wattage = Math.round(wattage * 10) * 0.1

		resultMap = createEvent(name: "power", value: wattage, unit: 'W')

        // Enable debug to see the parsed power usage zigbee message
		//log.debug "Power usage zigbee event reported as ${wattage} W"
	}
	else if (descMap.cluster == "000C" && descMap.attrId == "0055" && descMap.endpoint == "03") {
        def energy_int = Long.parseLong(descMap.value, 16)
		def energy = Float.intBitsToFloat(energy_int.intValue())

		energy = Math.round(energy * 100) * 0.0001

		resultMap = createEvent(name: "energy", value: energy, unit: 'kWh')

        // Enable debug to see the parsed energy consumption zigbee message
		//log.debug "Energy consumption zigbee event reported as ${energy} kWh"
	}
	return resultMap
}


// The following code should work later on for handling the power memory setting of ZNCZ02LM which is different to other Xiaomi outlets
//
// The cluster is the basicConfig one (0x000), Attribute ID is 0xFFF0, and type is 0x41 (Octet)
//
// I know the octet values is one of these, but not sure yet which:
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