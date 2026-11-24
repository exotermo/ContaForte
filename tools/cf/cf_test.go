package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func fakeGen(int) (string, error) { return strings.Repeat("a", 48), nil }
func fakeKey() (string, error)    { return "chave-falsa", nil }

func TestBuildEnvGeneratesMissing(t *testing.T) {
	env, created, err := buildEnv(map[string]string{}, false, fakeGen, fakeKey)
	if err != nil {
		t.Fatal(err)
	}
	for _, k := range secretKeys {
		if len(env[k]) < 24 {
			t.Errorf("segredo %s não gerado", k)
		}
	}
	if env["GATEWAY_PORT"] != "8080" || len(created) == 0 {
		t.Errorf("defaults ausentes: %v", env)
	}
}

func TestBuildEnvKeepsExistingSecrets(t *testing.T) {
	cur := map[string]string{"POSTGRES_PASSWORD": "mantenha-este-valor-com-24-chars"}
	env, _, _ := buildEnv(cur, false, fakeGen, fakeKey)
	if env["POSTGRES_PASSWORD"] != cur["POSTGRES_PASSWORD"] {
		t.Error("segredo existente foi sobrescrito")
	}
	env, _, _ = buildEnv(cur, true, fakeGen, fakeKey)
	if env["POSTGRES_PASSWORD"] == cur["POSTGRES_PASSWORD"] {
		t.Error("--force deveria regenerar")
	}
}

func TestBuildEnvReplacesPlaceholder(t *testing.T) {
	env, _, _ := buildEnv(map[string]string{"REDIS_PASSWORD": "change-me"}, false, fakeGen, fakeKey)
	if env["REDIS_PASSWORD"] == "change-me" {
		t.Error("placeholder deveria ser substituído")
	}
}

func TestEnvRoundTripAndPerm(t *testing.T) {
	p := filepath.Join(t.TempDir(), ".env")
	in := map[string]string{"A": "1", "B": "dois"}
	if err := writeEnv(p, in); err != nil {
		t.Fatal(err)
	}
	st, _ := os.Stat(p)
	if st.Mode().Perm() != 0o600 {
		t.Errorf("permissão %o, esperado 600", st.Mode().Perm())
	}
	out, err := parseEnv(p)
	if err != nil || out["A"] != "1" || out["B"] != "dois" {
		t.Errorf("round trip falhou: %v %v", out, err)
	}
}

func TestRandomHex(t *testing.T) {
	a, _ := randomHex(24)
	b, _ := randomHex(24)
	if len(a) != 48 || a == b {
		t.Errorf("randomHex inválido: %q %q", a, b)
	}
}

func TestWeakSecret(t *testing.T) {
	for _, v := range []string{"", "change-me", "curto", "Password"} {
		if !weakSecret(v) {
			t.Errorf("%q deveria ser fraco", v)
		}
	}
	if weakSecret(strings.Repeat("x", 48)) {
		t.Error("segredo longo não deveria ser fraco")
	}
}

func TestMemField(t *testing.T) {
	mi := "MemTotal:       16384000 kB\nMemAvailable:    8192000 kB\n"
	if v, err := memField(mi, "MemAvailable"); err != nil || v != 8000 {
		t.Errorf("got %d %v", v, err)
	}
	if _, err := memField(mi, "Nada"); err == nil {
		t.Error("esperava erro")
	}
}

func TestParsePSLinesAndArray(t *testing.T) {
	lines := `{"Name":"a-1","Service":"a","State":"running","Health":"healthy"}
{"Name":"b-1","Service":"b","State":"exited"}`
	s, err := parsePS(lines)
	if err != nil || len(s) != 2 || s[1].State != "exited" {
		t.Fatalf("linhas: %v %v", s, err)
	}
	s, err = parsePS(`[{"Service":"a","State":"running"}]`)
	if err != nil || len(s) != 1 {
		t.Fatalf("array: %v %v", s, err)
	}
	if s, _ := parsePS(""); s != nil {
		t.Error("vazio deveria dar nil")
	}
}

func TestPublishedOutsideLoopback(t *testing.T) {
	svcs := []svcInfo{
		{Service: "gateway", Publishers: []publisher{{URL: "127.0.0.1", PublishedPort: 8080}}},
		{Service: "postgres", Publishers: []publisher{{URL: "", TargetPort: 5432}}},
		{Service: "redis", Publishers: []publisher{{URL: "0.0.0.0", PublishedPort: 6379}}},
	}
	bad := publishedOutsideLoopback(svcs)
	if len(bad) != 1 || !strings.Contains(bad[0], "redis") {
		t.Errorf("esperado só redis, veio %v", bad)
	}
}

func TestBuildEnvJWTKeyGeneratedAndPreserved(t *testing.T) {
	env, _, _ := buildEnv(map[string]string{}, false, fakeGen, fakeKey)
	if env[jwtKeyName] != "chave-falsa" {
		t.Fatalf("chave JWT não gerada: %q", env[jwtKeyName])
	}
	cur := map[string]string{jwtKeyName: "minha-chave"}
	env, _, _ = buildEnv(cur, false, fakeGen, fakeKey)
	if env[jwtKeyName] != "minha-chave" {
		t.Error("chave JWT existente foi sobrescrita sem --force")
	}
	env, _, _ = buildEnv(cur, true, fakeGen, fakeKey)
	if env[jwtKeyName] != "chave-falsa" {
		t.Error("--force deveria regenerar a chave JWT")
	}
}

func TestJWTKeyRoundTrip(t *testing.T) {
	k, err := genJWTKey()
	if err != nil {
		t.Fatal(err)
	}
	bits, err := jwtKeyBits(k)
	if err != nil || bits != 3072 {
		t.Errorf("bits=%d err=%v", bits, err)
	}
	if _, err := jwtKeyBits("lixo"); err == nil {
		t.Error("chave inválida deveria dar erro")
	}
}

func TestPromTargets(t *testing.T) {
	raw := `{"status":"success","data":{"activeTargets":[
	  {"labels":{"job":"backend"},"health":"up","lastError":""},
	  {"labels":{"job":"loki"},"health":"down","lastError":"connection refused"}]}}`
	total, down, err := promTargets(raw)
	if err != nil || total != 2 || len(down) != 1 || down[0] != "loki" {
		t.Fatalf("total=%d down=%v err=%v", total, down, err)
	}
	if _, _, err := promTargets("não é json"); err == nil {
		t.Error("JSON inválido deveria dar erro")
	}
}

func TestTunnelEnabled(t *testing.T) {
	cases := map[string]bool{"": false, "tunnel": true, "a, tunnel": true, "tunnels": false, "other": false}
	for in, want := range cases {
		if got := tunnelEnabled(map[string]string{"COMPOSE_PROFILES": in}); got != want {
			t.Errorf("tunnelEnabled(%q) = %v, esperado %v", in, got, want)
		}
	}
}

func TestE2ECleanupSQLSoTocaNoPrefixoDeTeste(t *testing.T) {
	sql := strings.ToLower(e2eCleanupSQL)
	if strings.Count(sql, "like 'e2e-%'") != 3 {
		t.Fatalf("toda exclusão precisa ser limitada ao prefixo %q: %s", e2ePrefix, e2eCleanupSQL)
	}
	for _, stmt := range strings.Split(sql, ";") {
		stmt = strings.TrimSpace(stmt)
		if stmt != "" && strings.HasPrefix(stmt, "delete") && !strings.Contains(stmt, "e2e-%") {
			t.Fatalf("delete sem o filtro de prefixo: %s", stmt)
		}
	}
	// ordem exigida pelas chaves estrangeiras: transações, depois importações, depois contas
	tx, imp, acc := strings.Index(sql, "delete from transactions"), strings.Index(sql, "delete from imports"), strings.Index(sql, "delete from accounts")
	if tx < 0 || imp < 0 || acc < 0 || !(tx < imp && imp < acc) {
		t.Fatalf("ordem inválida (transações < importações < contas): %s", e2eCleanupSQL)
	}
}

func writeFile(t *testing.T, path, content string) {
	t.Helper()
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
}

func TestBenchCSVTemDatasNoPassadoELinhasDistintas(t *testing.T) {
	lines := strings.Split(strings.TrimSpace(string(benchCSV(5000))), "\n")
	if len(lines) != 5001 || lines[0] != "Data;Descricao;Valor" {
		t.Fatalf("formato inesperado: %d linhas, cabeçalho %q", len(lines), lines[0])
	}
	seen := map[string]bool{}
	hoje := time.Now().In(spZone).Format("2006-01-02")
	for _, l := range lines[1:] {
		if seen[l] {
			t.Fatalf("linha repetida: %s", l)
		}
		seen[l] = true
		f := strings.Split(l, ";")
		d, err := time.ParseInLocation("02/01/2006", f[0], spZone)
		if err != nil || d.Format("2006-01-02") > hoje {
			t.Fatalf("data inválida ou futura: %q", f[0])
		}
		if !strings.HasPrefix(f[2], "-") {
			t.Fatalf("valor deveria ser despesa: %q", f[2])
		}
	}
}
