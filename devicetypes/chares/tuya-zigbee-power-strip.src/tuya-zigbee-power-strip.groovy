/**
 *  Tuya Zigbee Power Strip TS0115 Parent Handler
 *  Device Handler for SmartThings
 *  Version 1.0 (Aug 2021) for new SmartThings App (2020)
 *  By Cristian H. Ares
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
 *  Missing Features: N/A
 *
 * --------------------------------------------------------------------------------------------------------------------------------------------------
 *
 *  Update Information: No Updates
 *
 * --------------------------------------------------------------------------------------------------------------------------------------------------
 * 
 * Notes:
 * 
 * Based on the generic zigbee 'multi switch' and 'multi switch power' handlers, and woobooung's integrated-zigbee-switch
 *
 * the following local flags "runLocally: true" and "executeCommandsLocally: true" will not allow the child devices to refresh.
 *
 */
 

metadata {
	definition(name: "Tuya Zigbee Power Strip", namespace: "chares", author: "chares", ocfDeviceType: "oic.d.smartplug", genericHandler: "Zigbee") {
		capability "Actuator"
		capability "Configuration"
		capability "Refresh"
		capability "Health Check"
		capability "Switch"

		command "childOn", ["string"]
		command "childOff", ["string"]

	}
	// simulator metadata
	simulator {
		// status messages
		status "on": "on/off: 1"
		status "off": "on/off: 0"

		// reply messages
		reply "zcl on-off on": "on/off: 1"
		reply "zcl on-off off": "on/off: 0"
	}

	fingerprint profileId: "0104", manufacturer: "_TYZB01_vkwryfdr", model: "TS0115", deviceJoinName: "Tuya Zigbee Power Strip"
}

def installed() {
	createChildDevices()
    updateDataValue("onOff", "catchall")
	refresh()
}

def updated() {
	for (child in childDevices) {
		if (!child.deviceNetworkId.startsWith(device.deviceNetworkId) || //parent DNI has changed after rejoin
				!child.deviceNetworkId.split(':')[-1].startsWith('0')) {
			child.setDeviceNetworkId("${device.deviceNetworkId}:0${getChildEndpoint(child.deviceNetworkId)}")
		}
	}
    updateDataValue("onOff", "catchall")
	refresh()
}

def parse(String description) {
	Map eventMap = zigbee.getEvent(description)
	Map eventDescMap = zigbee.parseDescriptionAsMap(description)

	if (eventMap) {
		if (eventDescMap && eventDescMap?.attrId == "0000") {//0x0000 : OnOff attributeId
			if (eventDescMap?.sourceEndpoint == "01") {
				sendEvent(eventMap)
			}
			else {
				def childDevice = childDevices.find {
					it.deviceNetworkId == "$device.deviceNetworkId:${eventDescMap.sourceEndpoint}" 
				}
				if (childDevice) {
					childDevice.sendEvent(eventMap)
				} 
				else {
					log.debug "Child device: $device.deviceNetworkId:${eventDescMap.sourceEndpoint} was not found"
				}
			}
		}
	}
}

private void createChildDevices() {
	if (!childDevices) {
		def childlist = [2,3,4,7]
		for (i in childlist) {
			if (i == 7) {
				addChildDevice("Tuya Zigbee Power Strip Outlet", "${device.deviceNetworkId}:0${i}", device.hubId, [completedSetup: true, label: "${device.displayName[0..-7]} USB", isComponent: false])				
			}
			else {
				addChildDevice("Tuya Zigbee Power Strip Outlet", "${device.deviceNetworkId}:0${i}", device.hubId, [completedSetup: true, label: "${device.displayName} ${i}", isComponent: false])
			}
		}
	}
}


def on() {
	zigbee.on()
}


def off() {
	zigbee.off()
}


def childOn(String dni) {
    def childEndpoint = getChildEndpoint(dni)
    if (childEndpoint == "ALL") {
        allChildOn()
        if (device.currentState("switch") != "on") {
            zigbee.on()
        }
    }
    else {
        zigbee.command(zigbee.ONOFF_CLUSTER, 0x01, "", [destEndpoint: childEndpoint])
    }
}


def childOff(String dni) {
    def childEndpoint = getChildEndpoint(dni)
    if (childEndpoint == "ALL") {
        allChildOff()
        if (device.currentState("switch") != "off") {
            zigbee.off()
        }
    }
    else {
        zigbee.command(zigbee.ONOFF_CLUSTER, 0x00, "", [destEndpoint: childEndpoint])
    }
}


private allChildOn() {
    childDevices.each {
        if (it.deviceNetworkId == "$device.deviceNetworkId:ALL") {
            it.sendEvent(name: "switch", value: "on") 
        } 
        else {
            if (it.currentState("switch").value != "on") {
                it.on()
            }
        }
    }
}


private allChildOff() {
    childDevices.each {
        if (it.deviceNetworkId == "$device.deviceNetworkId:ALL") {
            it.sendEvent(name: "switch", value: "off") 
        }
        else {
            if (it.currentState("switch").value != "off") {
                it.off()
            }
        }
    }
}


def ping() {
	return refresh()
}


def refresh() {
    def cmds = zigbee.onOffRefresh()
	def childlist = [2,3,4,7]
    for (i in childlist) {
        cmds += zigbee.readAttribute(zigbee.ONOFF_CLUSTER, 0x0000, [destEndpoint: i])
    }
    return cmds
}


def poll() {
	refresh()
}


def healthPoll() {
	def cmds = refresh()
	cmds.each { sendHubCommand(new physicalgraph.device.HubAction(it)) }
}


def configureHealthCheck() {
	if (!state.hasConfiguredHealthCheck) {
		log.info "Configuring Health Check, Reporting"
		unschedule("healthPoll")
		runEvery5Minutes("healthPoll")
		def healthEvent = [name: "checkInterval", value: 720, displayed: false, data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID]]
		// Device-Watch allows 2 check-in misses from device
		sendEvent(healthEvent)
		childDevices.each {
			it.sendEvent(healthEvent)
		}
		state.hasConfiguredHealthCheck = true
	}
}


def configure() {
	configureHealthCheck()

    //other devices supported by this DTH in the future
    def cmds = zigbee.onOffConfig(0, 120)
	def childlist = [2,3,4,7]
    for (i in childlist) {
        cmds += zigbee.configureReporting(zigbee.ONOFF_CLUSTER, 0x0000, 0x10, 0, 120, null, [destEndpoint: i])
    }
    cmds += refresh()
    return cmds
}


private getChildEndpoint(String dni) {
	dni.split(":")[-1] as Integer
}