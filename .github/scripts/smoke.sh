#!/bin/bash
# Teste de fumaça no emulador: instalação, atualização, abertura, escrita e persistência.
OUT="$GITHUB_WORKSPACE/ci-out"
mkdir -p "$OUT"
R="$OUT/resultado.txt"
log() { echo "$*" | tee -a "$R"; }
shot() { dismiss; adb exec-out screencap -p > "$OUT/$1.png"; }

# Fecha avisos do próprio emulador ("X isn't responding") que bloqueiam os toques.
dismiss() {
  for k in 1 2 3; do
    adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
    adb pull /sdcard/ui.xml "$OUT/ui.xml" > /dev/null 2>&1
    grep -q "responding" "$OUT/ui.xml" 2>/dev/null || return 0
    local xy
    xy=$(python3 "$GITHUB_WORKSPACE/.github/scripts/find.py" "$OUT/ui.xml" "Wait")
    [ -n "$xy" ] && adb shell input tap $xy && log "(aviso do sistema fechado)"
    sleep 2
  done
}

tap_text() {
  dismiss
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  adb pull /sdcard/ui.xml "$OUT/ui.xml" > /dev/null 2>&1
  local xy
  xy=$(python3 "$GITHUB_WORKSPACE/.github/scripts/find.py" "$OUT/ui.xml" "$1")
  if [ -z "$xy" ]; then log "FALHA: botão '$1' não encontrado"; return 1; fi
  adb shell input tap $xy
  log "ok: tocou em '$1' ($xy)"
}

adb wait-for-device
# Espera o sistema terminar de iniciar e assentar.
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done
sleep 45
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0

if [ -f old/InkFlow.apk ]; then
  adb install old/InkFlow.apk > "$OUT/install-old.txt" 2>&1
  log "instalar versão anterior: $(tail -1 "$OUT/install-old.txt")"
fi
adb install -r new/app-debug.apk > "$OUT/install-new.txt" 2>&1
log "instalar/atualizar versão nova: $(tail -1 "$OUT/install-new.txt")"
log "versão instalada: $(adb shell dumpsys package com.inkflow | grep -m1 versionName)"

adb logcat -c
adb shell am start -W -n com.inkflow/.MainActivity
sleep 8
shot 01-inicio

tap_text "Novo caderno"; sleep 3; shot 02-dialogo
tap_text "Criar"; sleep 6; shot 03-editor

# Escreve na parte de cima da página (acima da faixa de apoio da mão).
adb shell input swipe 300 450 1100 520 400
adb shell input swipe 300 600 1000 560 400
adb shell input swipe 400 700 700 800 300
sleep 2; shot 04-escrito

# Toque dentro da faixa de apoio (deve ser ignorado).
H=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1 | cut -dx -f2)
adb shell input swipe 300 $((H - 150)) 1200 $((H - 120)) 400
sleep 2; shot 05-toque-na-faixa

adb shell input keyevent BACK; sleep 3; shot 06-volta-inicio
adb shell am force-stop com.inkflow; sleep 1
adb shell am start -W -n com.inkflow/.MainActivity; sleep 6; shot 07-reaberto
tap_text "Sem título" || true; sleep 5; shot 08-caderno-reaberto

for tab in "Caneta" "Ajustes"; do tap_text "$tab" || true; sleep 3; shot "09-aba-$tab"; done

if adb shell pidof com.inkflow > /dev/null; then log "app rodando: SIM"; else log "app rodando: NÃO (fechou)"; fi
adb logcat -d > "$OUT/logcat.txt"
# Só conta falhas do próprio InkFlow.
grep -E "FATAL EXCEPTION" -A30 "$OUT/logcat.txt" | grep -B2 -A28 "com.inkflow" > "$OUT/crash.txt" || true
grep -E "ANR in com.inkflow" -A10 "$OUT/logcat.txt" >> "$OUT/crash.txt" || true
if [ -s "$OUT/crash.txt" ]; then log "CRASH ENCONTRADO (ver crash.txt)"; else log "nenhum crash no logcat"; fi
exit 0
