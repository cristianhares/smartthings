/**
 *  Xiaomi Zigbee Smart Outlet - model ZNCZ02LM (CN/AU/NZ/AR)
 *  Device Handler for SmartThings
 *  Version 1.1 for new SmartThings App (2020)
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
 *  Update 1.1: Fix for correct encoding data type
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

	preferences {
		// Temperature offset config
		input "tempOffset", "decimal", title:"Set temperature offset", description:"Adjust temperature in X degrees", range:"*..*"
	}
}

// Parse incoming device messages to generate events
def parse(String description) {
	// Enabling this debug will attempt to get all messages
    //log.debug "Incoming Zigbee parsed message: '${description}'"

	Map map = [:]

    def descMap = zigbee.parseDescriptionAsMap(description)

    // Enabling this debug will attempt to get the zigbee description message as a map
	//log.debug "Parsed Zigbee description as a map: ${descMap}"

	if (description?.startsWith('catchall:')) {
		map = parseCatchAllMessage(description)
	}
	else if (description?.startsWith('read attr -')) {
		map = parseReportAttributeMessage(description)
	}
	else if (description?.startsWith('on/off: ')){
		map = parseCustomMessage(description)
	}

	if (map) {
		// Enabling this debug will print the mapped message if ther is a map to be done
		//log.debug "The following mapped event is being created: ${map}"
		return createEvent(map)
	}
	else {
		return [:]
	}
}

// Function for when the catchall is detected
private Map parseCatchAllMessage(String description) {
	Map resultMap = [:]
	def zigbeeParse = zigbee.parse(description)

    // Enable debug to see the parsed zigbee message
    //log.debug "A catchall event was parsed as ${zigbeeParse}"

	if (zigbeeParse.clusterId == 0x0006 && zigbeeParse.command == 0x01){
		def onoff = zigbeeParse.data[-1]
		if (onoff == 1)
			resultMap = createEvent(name: "switch", value: "on")
		else if (onoff == 0)
			resultMap = createEvent(name: "switch", value: "off")
	}
	return resultMap
}

// Function for when a 'read attr' is detected
// The 'createEvent' module is what actually parsed the message into "temperature", "switch", "power", "energy"
private Map parseReportAttributeMessage(String description) {
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


// Function for when the on/off comes in a custom format
private Map parseCustomMessage(String description) {
	def result
	if (description?.startsWith('on/off: ')) {
		if (description == 'on/off: 0')
			result = createEvent(name: "switch", value: "off")
		else if (description == 'on/off: 1')
			result = createEvent(name: "switch", value: "on")
	}
	return result
}


// Add ping command for device-watch
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

    // The Xiaomi Outlet is not compliant with the ZCL standard, so simpleMeteringPowerConfig / simpleMeteringPowerRefresh / temperatureConfig cannot be used

    // The reporting config has to happen, as it is later used by the refresh() command while running the readAttribute() command
    zigbee.onOffConfig() + // Poll for the on/off status
	zigbee.configureReporting(0x0002, 0x0000, 0x29, 1, 300, 0x01) + // Set reporting time for temperature, which is INT16 (signed INT16)
	zigbee.configureReporting(0x000C, 0x0055, 0x39, 1, 300, 0x01, [destEndpoint: 0x0002]) + // Set reporting time for power, which is in FLOAT4
	zigbee.configureReporting(0x000C, 0x0055, 0x39, 1, 300, 0x01, [destEndpoint: 0x0003]) // Set reporting time for energy usage, which is in FLOAT4

    return refresh()
}


// Refresh command section (runs at refresh requests)
def refresh() {
    // Enable debug to see the execution of the 'refresh' command
	log.info "Running refresh command and requesting refresh values from device"

    // Read the zigbee clusters on refresh, the clusters are:
    // cluster 0 is unknown
    // cluster 1 attribute id 20 is battery (unknown if it exists at all)
    // cluster 2 attribute id 0 is the temperature (Non ZCL standard)
    // cluster 6 attribute id 0 is the on/off state, which can be obtained via the standard onOffRefresh()
    // cluster 0C, attribute 55 contains both the power (endpoint 02, in watts) and usage (endpoint 03 in kWh)

    zigbee.onOffRefresh() + // Poll for the on/off state
    zigbee.readAttribute(0x0002, 0x0000) + // Poll for the temperature in INT16
    zigbee.readAttribute(0x000C, 0x0055, [destEndpoint: 0x0002]) + // Poll for the power usage in Watts (FLOAT4)
    zigbee.readAttribute(0x000C, 0x0055, [destEndpoint: 0x0003]) // Poll for the energy usage in Kwh (FLOAT4)
}


// This is used to convert the Hex returned from battery and temperature
private Integer convertHexToInt(hex) {
	Integer.parseInt(hex,16)
}