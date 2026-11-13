#!/usr/bin/env python3
"""Builds app/src/main/assets/police_aircraft.txt from the FAA aircraft registry.

Usage: tools/build-police-aircraft.py MASTER.txt ACFTREF.txt > app/src/main/assets/police_aircraft.txt
Get both files from https://registry.faa.gov/database/ReleasableAircraft.zip (public domain).

Keeps aircraft whose registered owner is a law-enforcement agency. Output, one per line:
ICAO hex address <TAB> N-number <TAB> owner <TAB> make and model
"""
import csv
import re
import sys

# Owner names that mean law enforcement whoever registered them.
STRONG = re.compile(
    r"\b(POLICE|SHERIFF'?S?|STATE PATROL|HIGHWAY PATROL|STATE TROOPERS?|CONSTABLE|"
    r"LAW ENFORCEMENT|CUSTOMS AND BORDER|BORDER PROTECTION|DRUG ENFORCEMENT|"
    r"BUREAU OF INVESTIGATION|MARSHALS? SERVICE|HOMELAND SECURITY)\b"
)
# Broader names that only count when a government registered the aircraft.
GOVERNMENT_ONLY = re.compile(r"\b(PUBLIC SAFETY|PATROL|DEPARTMENT OF JUSTICE|DEPT OF JUSTICE)\b")


def main(master, acftref):
    models = {}
    with open(acftref, encoding="utf-8-sig", errors="replace") as f:
        for row in csv.DictReader(f):
            code = row["CODE"].strip()
            models[code] = f'{row["MFR"].strip().title()} {row["MODEL"].strip()}'.strip()
    out = []
    with open(master, encoding="utf-8-sig", errors="replace") as f:
        for row in csv.DictReader(f):
            name = " ".join(row["NAME"].split()).upper()
            government = row["TYPE REGISTRANT"].strip() == "5"
            if not (STRONG.search(name) or (government and GOVERNMENT_ONLY.search(name))):
                continue
            hexcode = row["MODE S CODE HEX"].strip().lower()
            if not re.fullmatch(r"[0-9a-f]{6}", hexcode):
                continue
            model = models.get(row["MFR MDL CODE"].strip(), "")
            out.append(f'{hexcode}\tN{row["N-NUMBER"].strip()}\t{name.title()}\t{model}')
    print("\n".join(sorted(set(out))))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
