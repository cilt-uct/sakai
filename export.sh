#!/bin/bash

echo -n "Username: "
read user

echo -n "Password: "
read -s pwd

session=$(curl -s --data-urlencode "id=${user}" --data-urlencode "pw=${pwd}" "http://127.0.0.1:8080/sakai-ws/rest/login/login")

if [ "$session" = "" ]; then
  echo "Login failed"
  exit
fi

for site in ${1+"$@"}; do
  echo "`date` :: Exporting site $site"
  curl -G -v --data-urlencode "sessionid=${session}" --data-urlencode "siteid=${site}" "http://127.0.0.1:8080/sakai-ws/rest/sakai/nyuArchiveSite"
  echo "`date` :: done"
done
