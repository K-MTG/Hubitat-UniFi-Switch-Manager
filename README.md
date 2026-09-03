# Hubitat UniFi Switch Port Manager

A Hubitat driver that exposes a single port on a UniFi switch as an on/off **Switch** device — switch on
restores normal network connectivity on the port; switch off blocks all traffic on it while leaving PoE
powered. Useful for cutting a PoE camera's network connection (stopping it from recording) without cutting
its power.

```text
┌─────────────────────────┐
│      Hubitat Hub         │
│                          │
│ UniFi Switch Port Manager│
└────────────▲─────────────┘
             │
             │ HTTPS (LAN)
             │
┌────────────┴─────────────┐
│  UniFi Network Controller │
│ (UDM / UDM Pro / UCG-Max) │
└────────────▲─────────────┘
             │
             │ adopted device
             │
┌────────────┴─────────────┐
│   UniFi Switch (PoE)      │
│  port_overrides config    │
└────────────▲─────────────┘
             │
             │ physical port
             │
┌────────────┴─────────────┐
│   Connected device         │
│   (e.g. a PoE camera)      │
└───────────────────────────┘
```

Turning the switch **on** activates the port:
- Assigns the port back to its configured network/VLAN
- Clears the traffic block (removes port security lockout)

Turning the switch **off** disables the port:
- Clears the port's native network assignment and locks out all tagged VLANs
- Blocks all traffic on the port — **PoE stays on**, so the connected device keeps power

---

## Why not just use `forward: "disabled"`?

There's no single "disable this port" field. UniFi's `forward` field (`all` / `native` / `customize` /
`disabled`) is computed server-side from the port's network assignment and can't be set directly — confirmed
live that PUTting it alone is silently ignored and reverts on the next read. The actual mechanism (reverse-
engineered from the controller UI's own network requests) is:

- **off()**: `native_networkconf_id: ""`, `tagged_vlan_mgmt: "block_all"`, `port_security_enabled: true`,
  `port_security_mac_address: []`
- **on()**: `native_networkconf_id: <resolved id>`, `tagged_vlan_mgmt: "auto"`, `port_security_enabled: false`,
  `port_security_mac_address: []`

`poe_mode`/PoE delivery are never touched by either — confirmed live that power stays on throughout.

---

## What you get

### Features

- ✅ One-click port disable/enable (traffic blocked, PoE untouched) from Hubitat, Dashboards, or Rule
  Machine, via a single API call
- ✅ Only the target port's override entry is mutated; every other port's config is round-tripped unchanged
  (the controller replaces the entire `port_overrides` array on every write, so this matters)
- ✅ Target network/VLAN resolved by **name** on every `on()`, not a cached id — a network being
  recreated/renumbered in the controller doesn't leave the driver pointing at a stale reference
- ✅ Switch located by MAC address, looked up fresh each command — no device ID to hunt down in the UI
- ✅ Cookie + CSRF session auth against the controller's private API; logs in fresh per command rather than
  caching a session token in device state (see Security Notes)
- ✅ `testConnection` command + `commStatus` attribute for verifying credentials without touching the port

### Limitations / Notes

- Ubiquiti's official local **API key** (Settings → Control Plane → Integrations) only grants access to the
  read-only Integration API — confirmed live that `PUT`/`PATCH` on `/devices/{id}` there returns `405 Method
  Not Allowed`. This driver needs a real local admin login, not an API key.
- `GET` on `/proxy/network/api/s/{site}/rest/device` (or `.../rest/device/{id}`) 404s on the tested firmware.
  Reads go through `stat/device` instead (returns the full device list; filtered client-side by MAC) — see
  the driver's file header for the confirmed request/response details.
- This driver targets **UniFi OS** controllers (UDM/UDM Pro/UDM SE/UCG-Max/Cloud Gateway, etc.), where API
  paths are prefixed with `/proxy/network` and login is `/api/auth/login`. A classic self-hosted controller
  or Cloud Key Gen1 uses `/api/login` with no `/proxy/network` prefix and would need the driver adjusted.
  Tested against Network application 7.4.1 on a UDM-family controller with a US 8 PoE 150W switch.
- The controller's local HTTPS certificate is self-signed; the driver ignores SSL validation errors
  (`ignoreSSLIssues`), same as the sibling [Camera Manager](https://github.com/K-MTG/Hubitat-UniFi-Camera-Manager)
  driver. This is only safe because the connection stays on your LAN.
- This is LAN-local. Do not expose the controller's HTTPS port to the public internet.

---

## Prerequisites

### Hardware
- UniFi Network controller (UniFi OS — UDM/UDM Pro/UDM SE/UCG-Max/Cloud Gateway)
- UniFi switch with the target port adopted on that controller
- Hubitat Hub

### Network
- Hubitat makes outbound HTTPS requests to the controller on port **443**
- **Static IP or DHCP reservation for the controller is required.** The driver stores the controller's IP in
  its preferences; if it changes, the driver stops working until you update it. A UDM/UDM Pro/Cloud Gateway
  is normally already static on your LAN as your gateway; if not, set a DHCP reservation for it.

### Credentials
- A **local admin account** on the controller — not an SSO-only cloud account, and not a local API key (API
  keys are Integration-API-only and cannot write port config; see Limitations).
  - Settings → Admins & Users → Add Admin, with **Local Access Only** and a role that can edit device
    settings (**Site Admin** is sufficient — confirmed live; Super Admin/Owner is not required).
  - It's worth creating a dedicated local admin for this rather than reusing your own — least privilege, easy
    to revoke independently.

### Info you'll need
- Controller IP address
- Site name (`default` unless you've renamed or added sites — Settings → System → Site Manager, or check the
  URL when logged into the controller UI)
- The switch's MAC address (printed on the device label, or Devices → the switch → Overview in the UniFi app)
- The port number
- The name of the network/VLAN the port should be assigned to when active (Settings → Networks — `Default`
  unless you've set up VLANs)

---

### Security Notes

- Treat the controller admin password like any other password — the driver stores it using Hubitat's
  `password` preference type, which is masked in the UI.
- The driver deliberately does **not** cache the session cookie or CSRF token in device state. Hubitat's
  device state is persisted and shown in plaintext in the device page's "State Variables" section (unlike
  the masked `password` field), so caching a live session there would leave a working bearer credential
  sitting around in the clear. Instead, the driver logs in fresh for every command — commands are infrequent
  user-triggered toggles, so the extra login round-trip is negligible.
- Keep the controller and Hubitat hub on a trusted LAN; do not port-forward the controller's HTTPS port.

---

## Getting Started

### Setup Hubitat Driver

1. In Hubitat, go to **Drivers Code**
2. Click **New Driver**, then either:
   - Paste the contents of [`drivers/unifi-switch-port.groovy`](drivers/unifi-switch-port.groovy), **or**
   - Use **Import** with:
     `https://raw.githubusercontent.com/K-MTG/hubitat-unifi-switch-manager/refs/heads/main/drivers/unifi-switch-port.groovy`
3. Click **Save**
4. Go to **Devices → Add Device → Virtual**, give it a name, and set **Type** to **UniFi Switch Port Manager**
5. Open the new device and fill in **Preferences**:
   - **UniFi Controller IP Address** — the controller's static IP / DHCP reservation
   - **Controller Local Admin Username** / **Controller Local Admin Password** — the local admin credentials
   - **Site Name** — usually `default`
   - **Switch MAC Address** — the target switch's MAC (e.g. `aa:bb:cc:dd:ee:ff`)
   - **Port Number** — the port to control
   - **Network/VLAN Name To Assign When Active** — usually `Default`
6. Click **Save Preferences**
7. Click **testConnection** and check the **commStatus** attribute / **Logs** to confirm the credentials work
8. Repeat steps 4-7 for each additional port — one device per port

Once configured, use the device's **on**/**off** commands (or add it to a Dashboard tile / Rule Machine rule)
to toggle the port between active and disabled.

---

## Components

### Driver: "UniFi Switch Port Manager"
- Implements Hubitat's `Switch` capability:
  - `on()` → resolves the configured network name to an id, assigns the port to it, and clears the traffic
    block
  - `off()` → clears the port's network assignment and locks out all tagged VLANs/MACs, blocking all traffic
    while leaving PoE untouched
- `testConnection` command re-authenticates and reports via the `commStatus` attribute (`online`/`offline`)
- Logs in fresh before every command (not cached in device state); retries once with a new session on a 401
- If a command fails (bad credentials, switch/network not found, network error), the driver logs a warning
  and leaves the `switch` attribute at its last known value rather than guessing
