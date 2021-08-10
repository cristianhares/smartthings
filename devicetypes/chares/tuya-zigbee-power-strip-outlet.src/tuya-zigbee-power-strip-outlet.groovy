/**
 *  Tuya Zigbee Power Strip TS0115 Child Handler
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
 */
 
metadata {
	definition(name: "Tuya Zigbee Power Strip Outlet", namespace: "chares", author: "chares", ocfDeviceType: "oic.d.smartplug", genericHandler: "Zigbee") {
		capability "Switch"
		capability "Actuator"
		capability "Sensor"
		capability "Health Check"
	}
}

def installed() {
	sendEvent(name: "checkInterval", value: 720, displayed: false, data: [protocol: "zigbee"])
}

void on() {
	parent.childOn(device.deviceNetworkId)
}

void off() {
	parent.childOff(device.deviceNetworkId)
}

void refresh() {
	parent.childRefresh(device.deviceNetworkId)
}

def uninstalled() {
	parent.delete()
}