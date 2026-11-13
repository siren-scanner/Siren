#!/usr/bin/env python3
"""Builds app/src/main/assets/airports.txt from OurAirports (public domain).

Usage: tools/build-airports.py airports.csv > app/src/main/assets/airports.txt
Get airports.csv from https://ourairports.com/data/.

Keeps large and medium airports and any with scheduled service that have an IATA or ICAO code.
Output, one per line: IATA <TAB> ICAO <TAB> name <TAB> city <TAB> country <TAB> latitude <TAB> longitude
"""
import csv
import sys


def main(path):
    out = []
    with open(path, encoding="utf-8") as f:
        for r in csv.DictReader(f):
            big = r["type"] in ("large_airport", "medium_airport") or r["scheduled_service"] == "yes"
            iata = r["iata_code"].strip().upper()
            icao = (r["icao_code"] or r["gps_code"]).strip().upper()
            if not big or r["type"] == "closed" or not (iata or len(icao) == 4):
                continue
            out.append("\t".join([
                iata, icao, r["name"].strip(), r["municipality"].strip(), r["iso_country"].strip(),
                "%.4f" % float(r["latitude_deg"]), "%.4f" % float(r["longitude_deg"]),
            ]))
    print("\n".join(sorted(out)))


if __name__ == "__main__":
    main(sys.argv[1])
