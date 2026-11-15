#!/usr/bin/env python3
import re
HOST = "zoo3"
with open("1_Client_non_Concurrent.log") as f:
    for line in f:
        if "logOpCodeDetails" not in line or f"host={HOST}" not in line:
            continue
        op = re.search(r"opcode=(\S+)", line)
        instr = re.search(r"instr=(\d+)", line)
        parent = re.search(r"parentId=(\S+)", line)
        self_ = re.search(r"selfId=(\S+)", line)
        if op and instr and parent and self_:
            print(f"op={op.group(1)} instr={instr.group(1)} parent={parent.group(1)} self={self_.group(1)}")
