@echo off
title FruverCopilot - Tunel 24/7 Keeper
echo ===================================================
echo     FRUVER COPILOT - TUNEL PERMANENTE 24/7
echo ===================================================
echo Dominio fijo: https://jurist-resubmit-ended.ngrok-free.dev
echo Manteniendo conexion activa cada 10 minutos y autorecuperando...
echo.
node scripts\tunnel_keeper.js
pause
