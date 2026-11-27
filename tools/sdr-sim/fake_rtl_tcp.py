#!/usr/bin/env python3
"""A pretend RTL-SDR dongle for testing Siren's radio features without hardware.

Speaks the rtl_tcp protocol (the 12-byte "RTL0" header, 5-byte commands, then a stream of 8-bit
I/Q samples) and streams noise plus scripted transmitters:

  * a public-safety radio keying up on 801.2063 MHz (700 MHz band) for 8 s every 40 s, after a
    quiet minute for Siren to learn the noise floor;
  * an always-on carrier on 803.5 MHz that Siren should learn to ignore;
  * a car's four tire-pressure sensors on 315 MHz (FSK, Manchester, 19.2 kbit/s): one burst of
    three repeated messages each time Siren tunes there, with the pressure changing a little.

Run it on the laptop, then point Siren's radio host at it (10.0.2.2 from the Android emulator):

    tools/sdr-sim/fake_rtl_tcp.py [--port 1234] [--quiet 60] [--every 40] [--on 8]

Plain Python, no numpy: each signal is precomputed once per tuned frequency and looped.
"""
import argparse
import math
import random
import socket
import struct
import threading
import time

NOISE_SIGMA = 4.0     # counts of noise on each of I and Q
BUF_SAMPLES = 1 << 17  # samples in each precomputed loop


class Transmitter:
    def __init__(self, name, hz, amplitude, schedule):
        self.name, self.hz, self.amplitude, self.schedule = name, hz, amplitude, schedule

    def on(self, t):
        return self.schedule(t)


TPMS_TUNE = 314_750_000
TIRE_IDS = [0x5EED0001, 0x5EED0002, 0x5EED0003, 0x5EED0004]


def tire_burst(rate, sensor, pressure, noise):
    """One sensor's transmission: three copies of its message, FSK 250 kHz above the tuned frequency."""
    payload = format(sensor, "032b") + format(pressure, "08b") + format(0x41, "08b") + "0010110110100110"
    halves = "".join("10" if b == "1" else "01" for b in "0" * 16 + "1" + payload)
    sph = rate / 38_400
    gap = int(rate * 0.004)
    out = bytearray()
    ph = 0.0
    idx = 0
    for _ in range(3):
        for n in range(int(len(halves) * sph)):
            f = 250_000 + (35_000 if halves[int(n / sph)] == "1" else -35_000)
            ph += 2 * math.pi * f / rate
            ni, nq = noise[idx % len(noise)]; idx += 1
            out.append(min(255, max(0, int(127.5 + ni + 35 * math.cos(ph)))))
            out.append(min(255, max(0, int(127.5 + nq + 35 * math.sin(ph)))))
        for _ in range(gap):
            ni, nq = noise[idx % len(noise)]; idx += 1
            out.append(min(255, max(0, int(127.5 + ni)))); out.append(min(255, max(0, int(127.5 + nq))))
    return bytes(out)


class Radio:
    def __init__(self, transmitters, start):
        self.tx = transmitters
        self.start = start
        self.center = 100_000_000
        self.rate = 1_024_000
        self.cache = {}
        rnd = random.Random(1)
        # One shared noise loop, as signed offsets.
        self.noise = [(rnd.gauss(0, NOISE_SIGMA), rnd.gauss(0, NOISE_SIGMA)) for _ in range(BUF_SAMPLES)]
        self.lock = threading.Lock()
        self.tuned_at = 0.0
        self.burst_done = True
        self.tire_n = 0

    def buffer(self, active):
        """I/Q bytes for the current tuning with the given transmitters keyed up (cached)."""
        key = (self.center, self.rate, tuple(sorted(t.name for t in active)))
        buf = self.cache.get(key)
        if buf is None:
            tones = [((t.hz - self.center) / self.rate, t.amplitude) for t in active
                     if abs(t.hz - self.center) < self.rate * 0.45]
            out = bytearray(2 * BUF_SAMPLES)
            for n, (ni, nq) in enumerate(self.noise):
                i, q = 127.5 + ni, 127.5 + nq
                for f, a in tones:
                    ph = 2 * math.pi * f * n
                    i += a * math.cos(ph)
                    q += a * math.sin(ph)
                out[2 * n] = min(255, max(0, int(i)))
                out[2 * n + 1] = min(255, max(0, int(q)))
            buf = bytes(out)
            self.cache[key] = buf
        return buf

    def active(self):
        t = time.time() - self.start
        return [tx for tx in self.tx if tx.on(t)]


def serve(conn, radio, log):
    conn.sendall(b"RTL0" + struct.pack(">II", 5, 29))  # tuner type R820T, 29 gain steps
    alive = [True]

    def commands():
        try:
            while alive[0]:
                cmd = conn.recv(5, socket.MSG_WAITALL)
                if len(cmd) < 5:
                    break
                op, val = cmd[0], struct.unpack(">I", cmd[1:])[0]
                with radio.lock:
                    if op == 0x01:
                        radio.center = val
                        radio.tuned_at = time.time()
                        radio.burst_done = False
                    elif op == 0x02:
                        radio.rate = val
                log.setdefault(op, 0)
                log[op] += 1
        finally:
            alive[0] = False

    threading.Thread(target=commands, daemon=True).start()
    pos, chunk = 0, 16384  # bytes per send
    sent_at = time.time()
    sent = 0
    was = set()
    try:
        while alive[0]:
            active = radio.active()
            names = {t.name for t in active}
            if names != was:
                print(time.strftime("%H:%M:%S"), "keyed up:", ", ".join(sorted(names)) or "(none)", flush=True)
                was = names
            with radio.lock:
                buf = radio.buffer(active)
                rate = radio.rate
                burst = None
                if radio.center == TPMS_TUNE and not radio.burst_done and time.time() - radio.tuned_at > 0.4 \
                        and time.time() - radio.start >= radio.tires_after:
                    radio.burst_done = True
                    radio.tire_n += 1
                    sensor = TIRE_IDS[radio.tire_n % len(TIRE_IDS)]
                    burst = tire_burst(rate, sensor, 0x60 + radio.tire_n % 5, radio.noise)
            if burst is not None:
                conn.sendall(burst)
                sent += len(burst)
                print(time.strftime("%H:%M:%S"), "tire sensor %08x sent" % TIRE_IDS[radio.tire_n % len(TIRE_IDS)], flush=True)
            if pos + chunk > len(buf):
                pos = 0
            conn.sendall(buf[pos:pos + chunk])
            pos += chunk
            sent += chunk
            # Real time: 2 bytes per sample.
            ahead = sent / (2 * rate) - (time.time() - sent_at)
            if ahead > 0:
                time.sleep(ahead)
    except OSError:
        pass
    finally:
        alive[0] = False
        conn.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=1234)
    ap.add_argument("--quiet", type=float, default=60, help="seconds before the first transmission")
    ap.add_argument("--every", type=float, default=40)
    ap.add_argument("--on", type=float, default=8)
    ap.add_argument("--tires-after", type=float, default=0, help="seconds before the car's tire sensors start")
    a = ap.parse_args()
    start = time.time()
    police = Transmitter("police radio 801.2063 MHz", 801_206_250, 40.0,
                         lambda t: t >= a.quiet and (t - a.quiet) % a.every < a.on)
    beacon = Transmitter("always-on carrier 803.5 MHz", 803_500_000, 25.0, lambda t: True)
    radio = Radio([police, beacon], start)
    radio.tires_after = a.tires_after
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", a.port))
    srv.listen(1)
    print(f"fake rtl_tcp on 127.0.0.1:{a.port} (10.0.2.2 from the emulator)", flush=True)
    while True:
        conn, addr = srv.accept()
        print(time.strftime("%H:%M:%S"), "client connected", addr, flush=True)
        log = {}
        serve(conn, radio, log)
        print(time.strftime("%H:%M:%S"), "client gone; commands seen:", log, flush=True)


if __name__ == "__main__":
    main()
