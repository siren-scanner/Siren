# Third-party data

Siren ships lookup tables used to guess who made a device (`app/src/main/assets`):

- `oui.txt` - network-hardware maker prefixes (MAC OUIs), derived from the Wireshark
  `manuf` file (GPL-2.0-or-later), which compiles the IEEE registries.
- `bt_companies.txt` and `bt_member_uuids.txt` - Bluetooth company identifiers and member service
  UUIDs, from the Bluetooth SIG's public Assigned Numbers.

- `police_aircraft.txt` - aircraft registered to law-enforcement agencies, filtered from the FAA
  aircraft registry (public domain) by `tools/build-police-aircraft.py`.

- The optional aircraft registry download (`aircraft-registry.txt.gz`, attached to each GitHub
  release) - make, model, year and owner of US aircraft from the FAA aircraft registry (public
  domain), built by `tools/build-aircraft-registry.py`. Optional online details come from
  [hexdb.io](https://hexdb.io), only if turned on.

- `airports.txt` - airport codes, names and positions from [OurAirports](https://ourairports.com)
  (public domain), built by `tools/build-airports.py`.

Police call data comes from cities' open-data portals, as listed in
`app/src/main/assets/police_feeds.json`: the City of New Orleans (NOPD calls for service; not for
commercial use), the City of Dallas (Dallas Police active calls) and the Utah Department of
Transportation (crash locations). Addresses are placed on the map
with OpenStreetMap's Nominatim search.

Live aircraft positions come from [adsb.lol](https://adsb.lol), open data under the Open Database
License. Map data © OpenStreetMap contributors, available under the Open Database License.
