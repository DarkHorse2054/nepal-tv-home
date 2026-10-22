"""Synthetic addresses/models only. No personal device configuration."""
from copy import deepcopy
FIRE = {"host": "192.168.1.100", "port": 5555, "model": "ExampleFireTV", "name": "Living room"}
SAMSUNG = {"host": "192.168.1.101", "port": 8002, "model": "ExampleSamsung"}
CLOCKS = {"local_timezone": "America/Los_Angeles", "local_label": "Local",
          "secondary_timezone": "Asia/Kathmandu", "secondary_label": "Secondary",
          "secondary_calendar": "bikram_sambat"}
def configuration():
    return deepcopy({"schema_version": 1, "fire_tv": FIRE, "clocks": CLOCKS, "samsung": SAMSUNG})
def serial():
    return FIRE["host"] + ":5555"
