@echo off
rem Double-click this to run the Campfyre coordinator with a visible log
rem window. Keep the window open while your group plays - closing it stops
rem the coordinator (group state is re-learned when players reconnect, and
rem saves live on disk in saves\, so restarting is always safe).
cd /d "%~dp0"
title Campfyre Coordinator
node server.js
pause
