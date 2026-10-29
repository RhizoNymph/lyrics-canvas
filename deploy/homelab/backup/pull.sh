#!/usr/bin/env bash
# Pull lyrics-canvas Postgres dumps from the backup node to this machine.
#
# Settings (environment, set by the systemd unit that install.sh renders):
#   BACKUP_SOURCE  rsync source: host:/dir over ssh, or a local /dir
#   BACKUP_DEST    absolute local directory; created mode 700 if missing
#   BACKUP_KEEP    keep the newest N dumps locally; 0 (default) keeps all
#
# Only finished dumps (lyrics_canvas-<YYYYMMDDTHHMMSSZ>.dump) are copied; the
# CronJob's hidden .partial temp files never match. Nothing is deleted locally
# because it vanished from the source (no --delete): the source prunes to its
# own retention, this side keeps its own history.
set -euo pipefail
export LC_ALL=C

log() {
  local level=$1 msg=$2
  shift 2
  local line="level=$level msg=\"$msg\""
  local kv
  for kv in "$@"; do line="$line $kv"; done
  if [[ $level == error ]]; then printf '%s\n' "$line" >&2; else printf '%s\n' "$line"; fi
}
die() { log error "$@"; exit 1; }

source_dir=${BACKUP_SOURCE:-}
dest=${BACKUP_DEST:-}
keep=${BACKUP_KEEP-0}
pattern='lyrics_canvas-[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]T[0-9][0-9][0-9][0-9][0-9][0-9]Z.dump'

[[ -n $source_dir ]] || die "BACKUP_SOURCE is not set"
[[ $dest == /* ]] || die "BACKUP_DEST must be an absolute path" "dest=$dest"
[[ $keep =~ ^[0-9]+$ ]] || die "BACKUP_KEEP must be a non-negative integer" "keep=$keep"

mkdir -p -- "$dest"
chmod 700 -- "$dest"

log info "pull started" "source=$source_dir" "dest=$dest"
if ! rsync --archive --timeout=600 --partial-dir=.rsync-partial \
  --include="$pattern" --exclude='*' \
  --rsh='ssh -o BatchMode=yes -o ConnectTimeout=15' \
  -- "${source_dir%/}/" "$dest/"; then
  die "rsync failed" "source=$source_dir"
fi
# --archive copies the source directory's mode onto dest; keep it private.
chmod 700 -- "$dest"

shopt -s nullglob
# Fixed-width UTC timestamps: glob order (C collation) is age order.
dumps=("$dest"/$pattern)
pruned=0
if ((keep > 0 && ${#dumps[@]} > keep)); then
  for old in "${dumps[@]:0:${#dumps[@]}-keep}"; do
    rm -f -- "$old"
    log info "pruned local dump" "file=$old"
    pruned=$((pruned + 1))
  done
fi
log info "pull complete" "local_dumps=$((${#dumps[@]} - pruned))" "pruned=$pruned" "keep=$keep"
