#!/usr/bin/env bash
# Install the systemd user timer that pulls lyrics-canvas dumps to this machine.
#
#   deploy/homelab/backup/install.sh [--host node1] \
#     [--path /home/nymph/backups/lyrics-canvas] [--dest ~/Backups/lyrics-canvas] \
#     [--keep 0] [--on-calendar '*-*-* 04:30:00'] [--no-enable]
#
# Copies pull.sh into --lib-dir, renders the .service/.timer templates into
# --unit-dir, reloads the user manager and enables the timer (unless
# --no-enable). Re-running replaces the previous install.
set -euo pipefail

here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
name=lyrics-canvas-backup-pull

host=node1
path=/home/nymph/backups/lyrics-canvas
dest="$HOME/Backups/lyrics-canvas"
keep=0
on_calendar='*-*-* 04:30:00'
unit_dir="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
lib_dir="$HOME/.local/libexec/lyrics-canvas"
enable=1

die() { printf 'level=error msg="%s"\n' "$*" >&2; exit 1; }
usage() { sed -n '2,11p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

need_arg() { [[ $# -ge 2 ]] || die "$1 needs a value"; }
while [[ $# -gt 0 ]]; do
  case $1 in
    --host) need_arg "$@"; host=$2; shift 2 ;;
    --path) need_arg "$@"; path=$2; shift 2 ;;
    --dest) need_arg "$@"; dest=$2; shift 2 ;;
    --keep) need_arg "$@"; keep=$2; shift 2 ;;
    --on-calendar) need_arg "$@"; on_calendar=$2; shift 2 ;;
    --unit-dir) need_arg "$@"; unit_dir=$2; shift 2 ;;
    --lib-dir) need_arg "$@"; lib_dir=$2; shift 2 ;;
    --no-enable) enable=0; shift ;;
    -h | --help) usage; exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
done

# Values land in unit files: refuse anything systemd would reinterpret
# (% specifiers, $ expansion, quotes, backslashes) rather than escaping it.
check_unit_safe() {
  local label=$1 value=$2
  [[ -n $value ]] || die "$label is empty"
  [[ $value != *[%\$\"\\]* && $value != *$'\n'* ]] || die "$label contains one of % \$ \" \\ or a newline: $value"
}
check_unit_safe --host "$host"
[[ $host != *[[:space:]/:]* ]] || die "--host must be an ssh host or user@host: $host"
check_unit_safe --path "$path"
[[ $path == /* ]] || die "--path must be absolute: $path"
check_unit_safe --dest "$dest"
[[ $dest == /* ]] || die "--dest must be absolute: $dest"
[[ $keep =~ ^[0-9]+$ ]] || die "--keep must be a non-negative integer: $keep"
check_unit_safe --on-calendar "$on_calendar"
check_unit_safe --unit-dir "$unit_dir"
check_unit_safe --lib-dir "$lib_dir"
[[ $lib_dir == /* ]] || die "--lib-dir must be absolute: $lib_dir"

source_spec="$host:$path"
pull_script="$lib_dir/backup-pull.sh"

render() {
  local template=$1 out=$2 text
  text=$(<"$template")
  text=${text//@SOURCE@/$source_spec}
  text=${text//@DEST@/$dest}
  text=${text//@KEEP@/$keep}
  text=${text//@PULL_SCRIPT@/$pull_script}
  text=${text//@ON_CALENDAR@/$on_calendar}
  printf '%s\n' "$text" > "$out.tmp"
  mv -- "$out.tmp" "$out"
}

mkdir -p -- "$unit_dir" "$lib_dir"
install -m 755 -- "$here/pull.sh" "$pull_script"
render "$here/$name.service.in" "$unit_dir/$name.service"
render "$here/$name.timer.in" "$unit_dir/$name.timer"
printf 'level=info msg="installed" source=%s dest=%s keep=%s unit_dir=%s\n' "$source_spec" "$dest" "$keep" "$unit_dir"

if ((enable)); then
  systemctl --user daemon-reload
  systemctl --user enable --now "$name.timer"
  systemctl --user list-timers "$name.timer" --no-pager
else
  printf 'level=info msg="not enabled" hint="systemctl --user daemon-reload && systemctl --user enable --now %s.timer"\n' "$name"
fi
