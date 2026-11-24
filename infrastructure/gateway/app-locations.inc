# Rotas compartilhadas pelos dois listeners (local e túnel). Variáveis definidas em cada server:
#   $cf_client_ip  IP do cliente   $cf_proto  esquema original (http/https)

# API: upstream via variável, o gateway sobe mesmo sem o backend.
location /api/ {
  client_max_body_size 6m;   # extratos de até 5 MB (o backend impõe o limite exato); o padrão do nginx seria 1 MB
  set $backend http://backend:8080;
  proxy_pass $backend;
  proxy_set_header Host $http_host;   # mantém a porta (a checagem de Origin do front compara host:porta)
  proxy_set_header X-Real-IP $cf_client_ip;
  proxy_set_header X-Forwarded-For $cf_client_ip;  # sobrescreve: o cliente não forja o próprio IP
  proxy_set_header X-Forwarded-Proto $cf_proto;
  error_page 502 503 504 = @unavailable;
}

# Frontend
location / {
  client_max_body_size 6m;   # o proxy de dados do front repassa o upload de extratos (/bff/imports)
  set $web http://web:3000;
  proxy_pass $web;
  proxy_set_header Host $http_host;
  proxy_set_header X-Real-IP $cf_client_ip;
  proxy_set_header X-Forwarded-For $cf_client_ip;
  proxy_set_header X-Forwarded-Proto $cf_proto;
  error_page 502 503 504 = @unavailable;
}

location @unavailable {
  default_type text/plain;
  return 503 "ContaForte: servico indisponivel\n";
}
