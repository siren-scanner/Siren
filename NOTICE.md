# Third-party data

Siren ships lookup tables used to guess who made a device (`app/src/main/assets`):

- `oui.txt` - network-hardware maker prefixes (MAC OUIs), derived from the Wireshark
  `manuf` file (GPL-2.0-or-later), which compiles the IEEE registries.
- `bt_companies.txt` and `bt_member_uuids.txt` - Bluetooth company identifiers and member service
  UUIDs, from the Bluetooth SIG's public Assigned Numbers.

Map data © OpenStreetMap contributors, available under the Open Database License.
