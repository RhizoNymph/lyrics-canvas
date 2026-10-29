{{/*
Backup container script (POSIX sh, run by /bin/sh -c). Inputs come from env:
BACKUP_DIR, BACKUP_RETAIN and the libpq PG* variables. Every failure exits
non-zero so the Job is marked failed.

Flow: validate inputs -> drop day-old partials -> pg_dump into a hidden temp
name -> verify the archive with pg_restore --list -> fsync -> rename to
lyrics_canvas-<UTC timestamp>.dump -> prune to the newest BACKUP_RETAIN dumps.
Readers (the laptop rsync) only pick up the final name, never the temp file.
*/}}
{{- define "lyrics-canvas.backup.script" -}}
set -eu
export LC_ALL=C
umask 077

log() {
  level=$1 msg=$2
  shift 2
  line="level=$level msg=\"$msg\""
  for kv in "$@"; do line="$line $kv"; done
  if [ "$level" = error ]; then printf '%s\n' "$line" >&2; else printf '%s\n' "$line"; fi
}
die() { log error "$@"; exit 1; }

dir=${BACKUP_DIR:-}
retain=${BACKUP_RETAIN:-}
prefix=lyrics_canvas
[ -n "$dir" ] || die "BACKUP_DIR is not set"
case $retain in ''|*[!0-9]*) die "BACKUP_RETAIN must be a positive integer" "retain=$retain" ;; esac
[ "$retain" -ge 1 ] || die "BACKUP_RETAIN must be a positive integer" "retain=$retain"
[ -n "${PGPASSWORD:-}" ] || die "PGPASSWORD is not set"
[ -d "$dir" ] || die "backup directory missing" "dir=$dir"
[ -w "$dir" ] || die "backup directory not writable" "dir=$dir" "uid=$(id -u)" "gid=$(id -g)"

# Partials from runs killed before their EXIT trap ran. A day old means no
# live run (the job's deadline is far shorter) can still own them.
find "$dir" -maxdepth 1 -type f -name ".$prefix-*.dump.partial" -mmin +1440 -exec rm -f {} +

ts=$(date -u +%Y%m%dT%H%M%SZ)
final="$dir/$prefix-$ts.dump"
tmp="$dir/.$prefix-$ts.dump.partial"
[ ! -e "$final" ] || die "dump already exists" "file=$final"

trap 'rm -f "$tmp"' EXIT
trap 'exit 143' TERM
trap 'exit 130' INT

log info "dump started" "host=${PGHOST:-}" "database=${PGDATABASE:-}" "file=$final"
pg_dump --format=custom --no-password --file="$tmp" || die "pg_dump failed" "host=${PGHOST:-}"
[ -s "$tmp" ] || die "pg_dump wrote an empty file"
pg_restore --list "$tmp" > /dev/null || die "dump is not a readable archive" "file=$tmp"
sync "$tmp"
mv "$tmp" "$final"
log info "dump written" "file=$final" "bytes=$(wc -c < "$final" | tr -d ' ')"

# Names embed a fixed-width UTC timestamp, so glob order (C collation) is age order.
set -- "$dir/$prefix"-[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]T[0-9][0-9][0-9][0-9][0-9][0-9]Z.dump
kept=0
if [ -e "$1" ]; then
  excess=$(($# - retain))
  while [ "$excess" -gt 0 ]; do
    rm -f -- "$1"
    log info "pruned old dump" "file=$1"
    shift
    excess=$((excess - 1))
  done
  kept=$#
fi
log info "backup complete" "kept=$kept" "retain=$retain"
{{- end }}
