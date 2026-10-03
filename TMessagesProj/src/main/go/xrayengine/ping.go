package main

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"time"

	"github.com/xtls/xray-core/core"
	xnet "github.com/xtls/xray-core/common/net"
)

// pingXray measures how long one HTTP request takes through the server in configJSON.
//
// It starts a private core instance next to the running tunnel: the config has no inbound, so it
// binds no port and cannot collide with the tunnel, and the request is handed to the instance's own
// dispatcher with core.Dial. Returns the time in milliseconds as a decimal string, or "error:" and
// the reason. The instance is always closed again, and a panic comes back as an error instead of
// taking the app down.
func pingXray(configJSON, method, target string, timeoutMs int) (result string) {
	defer func() {
		if r := recover(); r != nil {
			result = fmt.Sprintf("error:panic: %v", r)
		}
	}()
	if timeoutMs < 500 {
		timeoutMs = 500
	}
	timeout := time.Duration(timeoutMs) * time.Millisecond

	inst, err := core.StartInstance("json", []byte(configJSON))
	if err != nil {
		return "error:" + err.Error()
	}
	defer inst.Close()

	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	transport := &http.Transport{
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			dest, err := xnet.ParseDestination(network + ":" + addr)
			if err != nil {
				return nil, err
			}
			return core.Dial(ctx, inst, dest)
		},
		DisableKeepAlives:   true,
		TLSHandshakeTimeout: timeout,
	}
	defer transport.CloseIdleConnections()
	client := &http.Client{
		Transport: transport,
		Timeout:   timeout,
		// A redirect is an answer too; following it would measure a second server.
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
	req, err := http.NewRequestWithContext(ctx, method, target, nil)
	if err != nil {
		return "error:" + err.Error()
	}
	started := time.Now()
	resp, err := client.Do(req)
	if err != nil {
		return "error:" + err.Error()
	}
	resp.Body.Close()
	return fmt.Sprintf("%d", time.Since(started).Milliseconds())
}
