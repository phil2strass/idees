#!/bin/sh
set -eu
case " ${RENEWED_DOMAINS:-} " in
  *" ideesdesorties.eu "*|*" www.ideesdesorties.eu "*|*" alsace.ideesdesorties.eu "*)
    /usr/sbin/apache2ctl configtest
    systemctl reload apache2
    ;;
esac
