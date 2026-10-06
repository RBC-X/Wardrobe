"""Wardrobe Windows controls. Credentials never appear in command arguments or logs."""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.request
import webbrowser

ROOT=Path(__file__).resolve().parents[1]
DATA=Path(os.environ.get("WARDROBE_DATA",str(Path(os.environ.get("LOCALAPPDATA",str(Path.home())))/"WardrobeServer")))
URL="http://127.0.0.1:8765"


def health():
    try:
        with urllib.request.urlopen(URL+"/v1/health",timeout=2) as r:
            value=json.load(r)
            return value.get("app")=="Wardrobe" and value.get("schemaVersion")==1
    except (OSError,ValueError):return False


def start():
    if health():
        print("Wardrobe is already running.");return
    python=ROOT/".venv"/"Scripts"/"python.exe"
    if not python.is_file():raise RuntimeError("Run Setup.cmd first to install the private Python environment.")
    DATA.mkdir(parents=True,exist_ok=True)
    with (DATA/"server.log").open("ab") as log:
        subprocess.Popen([str(python),str(ROOT/"server"/"run.py")],cwd=ROOT,stdout=log,stderr=log,creationflags=getattr(subprocess,"CREATE_NO_WINDOW",0)|getattr(subprocess,"DETACHED_PROCESS",0))
    for _ in range(60):
        if health():print("Wardrobe started on localhost:8765.");return
        time.sleep(.5)
    raise RuntimeError("Wardrobe did not start. Inspect %LOCALAPPDATA%\\WardrobeServer\\server.log; another service may use port 8765.")


def stop():
    if not health():print("Wardrobe is not running.");return
    token=(DATA/"dashboard-token").read_text(encoding="utf-8")
    req=urllib.request.Request(URL+"/admin/shutdown",data=b"{}",headers={"X-Wardrobe-Admin":token,"Content-Type":"application/json"},method="POST")
    with urllib.request.urlopen(req,timeout=5) as r:json.load(r)
    for _ in range(65):
        if not health():print("Wardrobe stopped.");return
        time.sleep(.5)
    raise RuntimeError("Shutdown is still finishing an in-flight request; do not start a second server.")


def dashboard():
    start()
    token=(DATA/"dashboard-token").read_text(encoding="utf-8")
    webbrowser.open(URL+"/#admin="+token)
    print("Opened the local dashboard. Do not share its initial URL.")


def startup(enable):
    folder=Path(os.environ["APPDATA"])/"Microsoft"/"Windows"/"Start Menu"/"Programs"/"Startup"
    target=folder/"Wardrobe.cmd"
    if enable:
        folder.mkdir(parents=True,exist_ok=True)
        target.write_text('@echo off\r\ncall "'+str(ROOT/"Start Server.cmd")+'" --quiet\r\n',encoding="utf-8")
        print("Wardrobe will start at your next Windows sign-in.")
    else:
        if target.exists():target.unlink()
        print("Wardrobe startup disabled. Data is unchanged.")


def main():
    parser=argparse.ArgumentParser();parser.add_argument("action",choices=["start","stop","dashboard","status","enable-startup","disable-startup"]);args=parser.parse_args()
    if os.name!="nt":raise RuntimeError("These controls are for Windows.")
    if args.action=="start":start()
    elif args.action=="stop":stop()
    elif args.action=="dashboard":dashboard()
    elif args.action=="status":print("Wardrobe is running." if health() else "Wardrobe is stopped.")
    else:startup(args.action=="enable-startup")

if __name__=="__main__":
    try:main()
    except Exception as error:
        print("Wardrobe: "+str(error),file=sys.stderr);sys.exit(1)
