package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func cgroupFalso(t *testing.T, base, rel string, files map[string]string) {
	t.Helper()
	dir := filepath.Join(base, rel)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	for name, body := range files {
		if err := os.WriteFile(filepath.Join(dir, name), []byte(body), 0o644); err != nil {
			t.Fatal(err)
		}
	}
}

func TestReadMemUse(t *testing.T) {
	base := t.TempDir()
	cgroupFalso(t, base, "system.slice/docker-abc.scope", map[string]string{
		"memory.current": "1000\n", "memory.stat": "anon 500\ninactive_file 300\n",
		"memory.swap.current": "50\n", "memory.max": "2000\n", "memory.events": "oom 4\noom_kill 2\n",
	})
	cgroupFalso(t, base, "docker/def", map[string]string{"memory.current": "10\n", "memory.max": "max\n"})

	dir, err := cgroupDir(base, "abc")
	if err != nil {
		t.Fatal(err)
	}
	m, err := readMemUse(dir)
	if err != nil {
		t.Fatal(err)
	}
	if want := (memUse{Working: 700, Swap: 50, Limit: 2000, OOMKill: 2}); m != want || m.Total() != 750 {
		t.Fatalf("lido %+v (total %d), esperado %+v", m, m.Total(), want)
	}

	// layout do driver cgroupfs, sem limite, sem swap e sem memory.stat
	dir, err = cgroupDir(base, "def")
	if err != nil {
		t.Fatal(err)
	}
	if m, err = readMemUse(dir); err != nil || m != (memUse{Working: 10}) {
		t.Fatalf("lido %+v, erro %v", m, err)
	}

	if _, err := cgroupDir(base, "inexistente"); err == nil {
		t.Fatal("contêiner sem cgroup deveria dar erro")
	}
}

// O total simultâneo não é a soma dos picos: cada serviço atinge o seu em um momento.
func TestMemPeaks(t *testing.T) {
	var p memPeaks
	p.add(map[string]memUse{"backend": {Working: 100, Swap: 20}, "web": {Working: 80}})
	p.add(map[string]memUse{"backend": {Working: 150, OOMKill: 1}, "web": {Working: 10}})
	p.add(map[string]memUse{"backend": {Working: 90}, "web": {Working: 40}})

	if p.SumPeak != 200 {
		t.Fatalf("maior total simultâneo = %d, esperado 200", p.SumPeak)
	}
	if p.By["backend"].Total() != 150 || p.By["web"].Total() != 80 {
		t.Fatalf("picos por serviço errados: %+v", p.By)
	}
	if got := oomKilled(p); len(got) != 1 || got[0] != "backend" {
		t.Fatalf("o OOM visto numa amostra não pode se perder: %v", got)
	}
}

func TestCheckMemory(t *testing.T) {
	ok := memPeaks{By: map[string]memUse{"backend": {Working: 400, Limit: 1000}}}
	if err := checkMemory(ok); err != nil {
		t.Fatal(err)
	}
	for name, p := range map[string]memPeaks{
		"perto do limite contando o swap": {By: map[string]memUse{"backend": {Working: 500, Swap: 440, Limit: 1000}}},
		"OOM":                             {By: map[string]memUse{"web": {OOMKill: 1}}},
		"reinício":                        {Restarted: []string{"backend"}},
	} {
		if checkMemory(p) == nil {
			t.Errorf("%s deveria reprovar", name)
		}
	}
}

func TestChangedEMemTable(t *testing.T) {
	got := changed(map[string]string{"a": "0", "b": "1", "c": "0"}, map[string]string{"a": "0", "b": "2"})
	if strings.Join(got, ",") != "b,c" {
		t.Fatalf("mudados = %v", got)
	}

	var out strings.Builder
	writeMemTable(&out, memPeaks{
		By:        map[string]memUse{"web": {Working: 10 << 20}, "backend": {Working: 200 << 20, Swap: 5 << 20, Limit: 448 << 20, OOMKill: 1}},
		SumPeak:   215 << 20,
		Restarted: []string{"backend"},
	})
	s := out.String()
	for _, want := range []string{"OOM x1", "maior total simultâneo: 215 MiB", "soma dos limites: 448 MiB", "há serviço sem limite", "reiniciaram durante a medição: backend"} {
		if !strings.Contains(s, want) {
			t.Errorf("faltou %q em:\n%s", want, s)
		}
	}
	if strings.Index(s, "backend") > strings.Index(s, "web") {
		t.Error("o maior consumidor deve vir primeiro")
	}
}
