#!/usr/bin/env python3
import re
HOST = "zoo3"
edges = {}
nodes = set()
with open("1_Client_non_Concurrent.log") as f:
    for line in f:
        if "logOpCodeDetails" not in line or f"host={HOST}" not in line: continue
        op = re.search(r"opcode=(\S+)", line)
        instr = re.search(r"instr=(\d+)", line)
        parent = re.search(r"parentId=(\S+)", line)
        self_ = re.search(r"selfId=(\S+)", line)
        if not (op and instr and parent and self_): continue
        op_name = op.group(1); w = int(instr.group(1)); p = parent.group(1); s = self_.group(1)
        nodes.add(op_name)
        if p != "null" and p != s:
            edges[(p,s)] = edges.get((p,s),0) + w
print("digraph G {")
for n in sorted(nodes): print(f'  "{n}";')
for (p,c),w in edges.items(): print(f'  "{p}" -> "{c}" [label="instr={w}"];')
print("}")
