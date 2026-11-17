#!/usr/bin/env python3
"""Builds the optional aircraft registry download (aircraft-registry.txt.gz) from the FAA registry.

Usage: tools/build-aircraft-registry.py MASTER.txt ACFTREF.txt | gzip -9 > aircraft-registry.txt.gz
Get both files from https://registry.faa.gov/database/ReleasableAircraft.zip (public domain).

Format (UTF-8, tab-separated):
  #siren-aircraft-registry 1 <date of the FAA data>
  M <TAB> model number <TAB> make and model <TAB> seats       (one per aircraft model)
  ICAO hex <TAB> N-number <TAB> model number <TAB> year built <TAB> owner <TAB> city, state <TAB> engine
"""
import csv
import os
import re
import sys
import time

ENGINES = {"0": "", "1": "piston", "2": "turboprop", "3": "turboshaft", "4": "turbojet", "5": "turbofan",
           "6": "ramjet", "7": "2-cycle", "8": "4-cycle", "9": "", "10": "electric", "11": "rotary"}
# FAA names are all capitals; keep these as they are when title-casing.
KEEP_UPPER = {"LLC", "LLP", "LP", "NA", "USA", "US", "II", "III", "IV", "FBO", "FAA", "NASA", "DBA", "AVN"}


def tidy(s):
    words = []
    for w in " ".join(s.split()).split(" "):
        words.append(w if w.upper() in KEEP_UPPER or any(c.isdigit() for c in w) else w.capitalize())
    return " ".join(words).strip()


def main(master, acftref):
    models, model_index, model_lines = {}, {}, []
    with open(acftref, encoding="utf-8-sig", errors="replace") as f:
        for row in csv.DictReader(f):
            name = f'{tidy(row["MFR"])} {row["MODEL"].strip()}'.strip()
            seats = row.get("NO-SEATS", "").strip().lstrip("0")
            models[row["CODE"].strip()] = (name, seats)
    out = []
    with open(master, encoding="utf-8-sig", errors="replace") as f:
        for row in csv.DictReader(f):
            hexcode = row["MODE S CODE HEX"].strip().lower()
            if not re.fullmatch(r"[0-9a-f]{6}", hexcode):
                continue
            name, seats = models.get(row["MFR MDL CODE"].strip(), ("", ""))
            if name not in model_index:
                model_index[name] = len(model_index)
                model_lines.append(f"M\t{model_index[name]}\t{name}\t{seats}")
            place = ", ".join(p for p in (tidy(row["CITY"]), row["STATE"].strip()) if p)
            year = row["YEAR MFR"].strip()
            engine = ENGINES.get(row["TYPE ENGINE"].strip(), "")
            out.append("\t".join([hexcode, "N" + row["N-NUMBER"].strip(), str(model_index[name]), year,
                                  tidy(row["NAME"]), place, engine]))
    date = time.strftime("%Y-%m-%d", time.gmtime(os.path.getmtime(master)))
    print(f"#siren-aircraft-registry 1 {date}")
    print("\n".join(model_lines))
    print("\n".join(sorted(out)))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
