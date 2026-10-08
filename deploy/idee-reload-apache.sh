#!/bin/sh
set -eu
case " ${RENEWED_DOMAINS:-} " in
  *" idees.cavousdit.com "*)
    /usr/sbin/apache2ctl configtest
    systemctl reload apache2
    ;;
esac
