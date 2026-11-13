# Third-party data

Siren ships lookup tables used to guess who made a device (`app/src/main/assets`):

- `oui.txt` - network-hardware maker prefixes (MAC OUIs), derived from the Wireshark
  `manuf` file (GPL-2.0-or-later), which compiles the IEEE registries.
- `bt_companies.txt` and `bt_member_uuids.txt` - Bluetooth company identifiers and member service
  UUIDs, from the Bluetooth SIG's public Assigned Numbers.

- `police_aircraft.txt` - aircraft registered to law-enforcement agencies, filtered from the FAA
  aircraft registry (public domain) by `tools/build-police-aircraft.py`.

Live aircraft positions come from [adsb.lol](https://adsb.lol), open data under the Open Database
License. Map data © OpenStreetMap contributors, available under the Open Database License.
