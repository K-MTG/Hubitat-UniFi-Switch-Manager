/**
 * UniFi Switch Port Manager
 *
 * Filename: unifi-switch-port.groovy
 * Version:  0.1.0
 *
 * Description:
 * - Represents a single port on a UniFi switch (e.g. a PoE port feeding a
 *   camera) as a Hubitat Switch
 * - on()  -> port forwards traffic normally, assigned to the configured
 *            network/VLAN
 * - off() -> port traffic is fully blocked; PoE stays on (device keeps
 *            power, just loses network connectivity - e.g. to stop a
 *            camera from recording without powering it off)
 * - Talks to the UniFi Network controller's private/internal API (the same
 *   one its own web UI uses), not the official read-only Integration API
 *   (see Notes)
 * - Cookie + CSRF session auth (POST /api/auth/login) for every command;
 *   the session is NOT cached in device state (see Notes)
 *
 * Notes:
 * - This driver targets UniFi OS controllers (UDM/UDM Pro/UDM SE/UCG-Max/
 *   Cloud Gateway, etc.) where API paths are prefixed with /proxy/network.
 *   A classic self-hosted controller or Cloud Key Gen1 would need that
 *   prefix removed and /api/login instead of /api/auth/login.
 * - Ubiquiti's official local "API key" feature (Settings > Control Plane
 *   > Integrations) only grants access to the read-only Integration API
 *   (/proxy/network/integration/v1/...) - confirmed live that PUT/PATCH
 *   on that API's /devices/{id} returns 405 Method Not Allowed. Writing
 *   port config requires a real local admin account and the private API
 *   the web UI itself uses, authenticated via session cookie + CSRF token.
 * - GET on /proxy/network/api/s/{site}/rest/device (or .../rest/device/
 *   {id}) 404s on the tested firmware. Reads go through
 *   /proxy/network/api/s/{site}/stat/device (returns the full device list
 *   including port_overrides; filter client-side by _id/mac). Writes go
 *   through PUT /proxy/network/api/s/{site}/rest/device/{id}, which
 *   replaces the entire port_overrides array - not a per-port patch - so
 *   this driver always fetches the current array and mutates only the one
 *   port entry before writing it back.
 * - There is no direct "disable this port" field. The `forward` field
 *   (all/native/customize/disabled) is server-derived and read-only -
 *   confirmed live that PUTting forward:"disabled" by itself is silently
 *   ignored and reverts to its previous value on the next read. The
 *   actual mechanism (reverse-engineered from the controller UI's own
 *   network-tab requests) is to clear the port's native network and lock
 *   out all tagged VLANs:
 *     off(): native_networkconf_id: "", tagged_vlan_mgmt: "block_all",
 *            port_security_enabled: true, port_security_mac_address: []
 *     on():  native_networkconf_id: <resolved id>, tagged_vlan_mgmt:
 *            "auto", port_security_enabled: false,
 *            port_security_mac_address: []
 *   poe_mode/port_poe are never touched by this driver - confirmed live
 *   that PoE delivery is unaffected by the above in both directions.
 * - The target network/VLAN is looked up by name (rest/networkconf) on
 *   every on(), rather than caching its Mongo _id, so a network being
 *   recreated/renumbered doesn't leave the driver pointing at a stale id.
 * - The session cookie/CSRF token are deliberately not cached in `state`
 *   for the same reason as the sibling UniFi Camera Manager driver:
 *   Hubitat's device state is shown in plaintext in the UI, unlike the
 *   masked `password` preference field, so caching a live session there
 *   would leave a working bearer credential sitting around in the clear.
 *
 * Changes (0.1.0):
 * - Initial Release
 */

metadata {
    definition(
        name: "UniFi Switch Port Manager",
        namespace: "k-mtg",
        author: "K-MTG",
        importUrl: "https://raw.githubusercontent.com/K-MTG/hubitat-unifi-switch-manager/refs/heads/main/drivers/unifi-switch-port.groovy"
    ) {
        capability "Switch"
        capability "Actuator"

        command "testConnection"

        attribute "commStatus", "string"
    }

    preferences {
        input name: "controllerIp", type: "string", title: "UniFi Controller IP Address", required: true
        input name: "controllerUsername", type: "string", title: "Controller Local Admin Username", required: true
        input name: "controllerPassword", type: "password", title: "Controller Local Admin Password", required: true
        input name: "siteName", type: "string", title: "Site Name", defaultValue: "default", required: true
        input name: "switchMac", type: "string", title: "Switch MAC Address (e.g. aa:bb:cc:dd:ee:ff)", required: true
        input name: "portIdx", type: "number", title: "Port Number", required: true
        input name: "activeNetworkName", type: "string", title: "Network/VLAN Name To Assign When Active", defaultValue: "Default", required: true
        input name: "debugLogging", type: "bool", title: "Enable debug logging", defaultValue: false
    }
}

/* ================= Lifecycle ================= */

def installed() {
    logInfo "Installed"
    sendEvent(name: "switch", value: "on")
    sendEvent(name: "commStatus", value: "unknown")
}

def updated() {
    logInfo "Updated"

    unschedule("logsOff")
    if (debugLogging) {
        runIn(1800, "logsOff")
    }
}

private void logsOff() {
    logInfo "Debug logging auto-disabled"
    device.updateSetting("debugLogging", [value: "false", type: "bool"])
}

/* ================= Capability: Switch ================= */

def on() {
    logInfo "Activating port ${portIdx} (assigning to network '${activeNetworkName}')"

    if (applyPortState(true)) {
        sendEvent(name: "switch", value: "on")
        logInfo "Port ${portIdx} active"
    } else {
        logWarn "Activate failed; leaving switch state unchanged"
    }
}

def off() {
    logInfo "Disabling port ${portIdx} (traffic blocked, PoE left on)"

    if (applyPortState(false)) {
        sendEvent(name: "switch", value: "off")
        logInfo "Port ${portIdx} disabled"
    } else {
        logWarn "Disable failed; leaving switch state unchanged"
    }
}

/* ================= Diagnostics ================= */

def testConnection() {
    logInfo "Testing connection / credentials"

    if (login()) {
        sendEvent(name: "commStatus", value: "online")
        logInfo "Connection OK"
    } else {
        sendEvent(name: "commStatus", value: "offline")
        logWarn "Connection failed - check controller IP/username/password"
    }
}

/* ================= Controller API ================= */

/**
 * Logs in, finds the target switch + (for on()) resolves the target
 * network's id, mutates only this port's override entry, and PUTs the
 * full port_overrides array back. Retries once (with a fresh login) on
 * a 401 from the write.
 */
private boolean applyPortState(boolean active) {
    for (int attempt = 1; attempt <= 2; attempt++) {
        Map session = login()
        if (!session) return false

        Map switchDevice = findSwitchDevice(session)
        if (!switchDevice) {
            logWarn "Switch with MAC ${switchMac} not found on site '${siteName}'"
            return false
        }

        String targetNetworkId = ""
        if (active) {
            targetNetworkId = findNetworkIdByName(session, activeNetworkName)
            if (targetNetworkId == null) {
                logWarn "Network '${activeNetworkName}' not found on site '${siteName}'"
                return false
            }
        }

        List overrides = (switchDevice.port_overrides ?: []).collect { new LinkedHashMap(it) }
        Integer targetIdx = portIdx as Integer
        Map portOverride = overrides.find { (it.port_idx as Integer) == targetIdx }
        if (!portOverride) {
            portOverride = [port_idx: targetIdx, poe_mode: "auto"]
            overrides << portOverride
        }

        if (active) {
            portOverride.native_networkconf_id = targetNetworkId
            portOverride.tagged_vlan_mgmt = "auto"
            portOverride.port_security_enabled = false
            portOverride.port_security_mac_address = []
        } else {
            portOverride.native_networkconf_id = ""
            portOverride.tagged_vlan_mgmt = "block_all"
            portOverride.port_security_enabled = true
            portOverride.port_security_mac_address = []
        }

        Integer status = putPortOverrides(switchDevice._id as String, overrides, session)
        if (status != null && status < 300) {
            return true
        }
        if (status == 401 && attempt == 1) {
            logDebug "Got 401, retrying with a fresh session"
            continue
        }
        logWarn "PUT port_overrides failed (status=${status})"
        return false
    }
    return false
}

/**
 * stat/device returns the full device list (GET on rest/device 404s on
 * this firmware - see file header Notes). Filtered client-side by MAC.
 */
private Map findSwitchDevice(Map session) {
    Map params = [
        uri            : "https://${controllerIp}",
        path           : "/proxy/network/api/s/${siteName}/stat/device",
        headers        : ["Cookie": session.cookie, "X-Csrf-Token": session.csrf],
        contentType    : "application/json",
        ignoreSSLIssues: true,
        timeout        : 15
    ]

    String macLower = switchMac?.toLowerCase()
    Map found = null
    try {
        httpGet(params) { resp ->
            found = resp.data?.data?.find { (it.mac as String)?.toLowerCase() == macLower }
        }
    } catch (Exception e) {
        logWarn "Failed to fetch device list: ${e.message}"
    }
    return found
}

private String findNetworkIdByName(Map session, String name) {
    Map params = [
        uri            : "https://${controllerIp}",
        path           : "/proxy/network/api/s/${siteName}/rest/networkconf",
        headers        : ["Cookie": session.cookie, "X-Csrf-Token": session.csrf],
        contentType    : "application/json",
        ignoreSSLIssues: true,
        timeout        : 15
    ]

    String id = null
    try {
        httpGet(params) { resp ->
            def match = resp.data?.data?.find { it.name == name }
            id = match?._id
        }
    } catch (Exception e) {
        logWarn "Failed to fetch network list: ${e.message}"
    }
    return id
}

private Integer putPortOverrides(String deviceId, List overrides, Map session) {
    Map params = [
        uri               : "https://${controllerIp}",
        path              : "/proxy/network/api/s/${siteName}/rest/device/${deviceId}",
        headers           : ["Cookie": session.cookie, "X-Csrf-Token": session.csrf],
        body              : [port_overrides: overrides],
        requestContentType: "application/json",
        contentType       : "application/json",
        ignoreSSLIssues   : true,
        timeout           : 15
    ]

    Integer status = null
    try {
        httpPut(params) { resp ->
            status = resp.status
            logDebug "PUT device/${deviceId} -> ${status}"
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        status = e.response?.status
        logDebug "PUT device/${deviceId} -> ${status} (exception)"
    } catch (Exception e) {
        logWarn "PUT device/${deviceId} error: ${e.message}"
    }
    return status
}

/* ================= HTTP / Auth ================= */

/**
 * Logs in and returns [cookie, csrf], or null on failure. Deliberately
 * not cached in `state` - see file header Notes. The CSRF token is
 * returned directly as an X-Csrf-Token response header on UniFi OS
 * controllers - no JWT cookie decoding needed.
 */
private Map login() {
    if (!controllerIp || !controllerUsername || !controllerPassword) {
        logWarn "Cannot login: controller IP/username/password not configured"
        return null
    }

    Map params = [
        uri               : "https://${controllerIp}",
        path              : "/api/auth/login",
        body              : [username: controllerUsername, password: controllerPassword],
        requestContentType: "application/json",
        contentType       : "application/json",
        ignoreSSLIssues   : true,
        timeout           : 15
    ]

    String cookie = null
    String csrf = null
    try {
        httpPost(params) { resp ->
            String setCookie = resp.headers?.'Set-Cookie'
            if (setCookie) {
                cookie = setCookie.tokenize(';')[0].trim()
            }
            csrf = resp.headers?.'X-Csrf-Token'
        }
    } catch (Exception e) {
        logWarn "Login failed: ${e.message}"
        return null
    }

    if (!cookie || !csrf) {
        logWarn "Login response missing session cookie or CSRF token"
        return null
    }

    logDebug "Login OK"
    return [cookie: cookie, csrf: csrf]
}

/* ================= Logging ================= */

private logDebug(msg) { if (debugLogging) log.debug "${device.displayName}: ${msg}" }
private logInfo(msg)  { log.info  "${device.displayName}: ${msg}" }
private logWarn(msg)  { log.warn  "${device.displayName}: ${msg}" }
