# UniFi Switch Port Manager

A Hubitat driver that exposes a single UniFi switch port as a Switch device — `on()` restores normal network connectivity on the port, `off()` blocks all traffic on it while leaving PoE powered. Useful for cutting a PoE camera's network connection (stopping it from recording) without cutting its power.

## How it works

```
Hubitat (this driver)
   |
   |  HTTPS (local network)
   v
UniFi Network Controller (UDM / UDM Pro / UDM SE / UCG-Max / Cloud Gateway)
   |
   v
UniFi switch (e.g. US 8 PoE 150W) -- port_overrides config
   |
   v
Physical port -> connected device (e.g. a PoE camera)
```

This driver talks to the controller's **private/internal API** — the same one its own web UI uses — not Ubiquiti's official, read-only Integration API (local API keys only cover that one; it has no endpoint for changing port config as of this writing). That means it needs a real controller admin login, not just an API key.

## Why not just use `forward: "disabled"`?

There's no single "disable this port" field. UniFi's `forward` field (`all` / `native` / `customize` / `disabled`) is computed server-side from the port's network assignment and can't be set directly — confirmed live that PUTting it alone is silently ignored. The actual mechanism (reverse-engineered from the controller UI's own network requests) is:

- **off()**: clear the port's native network and lock out all tagged VLANs — `native_networkconf_id: ""`, `tagged_vlan_mgmt: "block_all"`, `port_security_enabled: true`, `port_security_mac_address: []`
- **on()**: assign it back to the configured network — `native_networkconf_id: <resolved id>`, `tagged_vlan_mgmt: "auto"`, `port_security_enabled: false`

`poe_mode`/PoE delivery are never touched by either — confirmed live that power stays on throughout.

## Features

- Single combined write per command (fetches the port's current override, mutates only the target port entry, writes the full array back — the controller replaces the whole array on every PUT, so untouched ports are preserved by round-tripping them unchanged)
- Target network/VLAN resolved by **name** on every `on()` (not a cached ID), so a network being recreated/renumbered in the controller doesn't leave the driver pointing at a stale reference
- Switch identified by MAC address, looked up fresh each command — no device ID to hunt down in the UI
- Session cookie + CSRF token obtained fresh per command, never cached in Hubitat device state (see Security Notes)
- `testConnection` command to verify controller credentials independent of touching the port

## Prerequisites

### Network
- The UniFi controller needs a reachable local IP. A UDM/UDM Pro/Cloud Gateway is normally already static on your LAN; if not, set a DHCP reservation for it in its own UniFi Network settings.
- Hubitat needs local HTTPS access (443) to the controller. The controller's certificate is self-signed — this driver ignores SSL verification for that reason (see Security Notes).

### Credentials
- A **local admin account** on the controller (not an SSO-only cloud account, and not a local API key — API keys are Integration-API-only and cannot write port config). Settings > Admins & Users > Add Admin, with "Local Access Only" and a role that can edit device settings.
- It's worth creating a dedicated local admin for this rather than reusing your own — same reasoning as any automation credential: least privilege, easy to revoke independently.

### Info you'll need
- Controller IP address
- Site name (`default` unless you've renamed or added sites — Settings > System > Site Manager, or just check the URL when logged into the controller UI)
- The switch's MAC address (printed on the device label, or Devices > the switch > Overview in the UniFi app)
- The port number
- The name of the network/VLAN the port should be assigned to when active (Settings > Networks — `Default` unless you've set up VLANs)

## Security Notes

- The controller password is stored using Hubitat's masked `password` preference type, not plain text.
- The session cookie and CSRF token are **not** cached in Hubitat's `state` — unlike the masked password field, `state` is rendered in plaintext in the device page's "State Variables" section, so caching a live session there would leave a working bearer credential sitting around in the clear. This driver logs in fresh on every command instead; these are infrequent user-triggered toggles, so the login overhead is negligible.
- `ignoreSSLIssues: true` is used because the controller's local HTTPS certificate is self-signed. This is standard for local-network UniFi API access but does mean the driver isn't validating the certificate — acceptable for same-LAN traffic, not something you'd want over an untrusted network.

## Getting Started (Hubitat)

1. In the Hubitat admin UI, go to **Drivers Code** > **New Driver**.
2. Paste the contents of `drivers/unifi-switch-port.groovy`, or use **Import** with the raw GitHub URL in the file's `importUrl`.
3. Click **Save**.
4. Go to **Devices** > **Add Device** > **Virtual** (Device Type: match the driver's name, "UniFi Switch Port Manager").
5. Give the device a name (e.g. `Driveway Camera Port` or `Switch Port 7`).
6. Open the new device, fill in **Preferences**: controller IP, admin username/password, site name, switch MAC, port number, and the active network name.
7. Click **Save Preferences**.
8. Run **testConnection** and check the `commStatus` attribute / Logs — it should report `online`. If it reports `offline`, double-check the controller IP and admin credentials.
9. Use the On/Off buttons (or automations) to toggle the port. Check the switch's port state in the UniFi app to confirm it's taking effect.

## Components

- **on()** — resolves the configured network name to an id, sets the port's native network to it, clears VLAN/port-security restrictions, and re-enables forwarding. Sets the `switch` attribute to `on` on success.
- **off()** — clears the port's native network assignment and locks out all tagged VLANs and unlisted MACs, blocking all traffic while leaving PoE untouched. Sets the `switch` attribute to `off` on success.
- **testConnection()** — logs in and reports `commStatus` as `online`/`offline`, without touching any port.

If a command fails (bad credentials, switch/network not found, network error), the driver logs a warning and leaves the `switch` attribute at its last known value rather than guessing.
