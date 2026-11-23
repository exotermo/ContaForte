package main

import (
	"io/fs"
	"strconv"
	"syscall"
)

// dockerSockGID devolve o GID dono do docker.sock (para --group-add no container de testes).
func dockerSockGID(info fs.FileInfo) string {
	if st, ok := info.Sys().(*syscall.Stat_t); ok {
		return strconv.Itoa(int(st.Gid))
	}
	return "0"
}
