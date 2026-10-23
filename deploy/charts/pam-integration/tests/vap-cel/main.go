// Đánh giá ValidatingAdmissionPolicy ngoài cụm (INF-27): môi trường CEL gốc của kube-apiserver 1.33 (k8s.io/apiserver),
// đối tượng dạng unstructured như VAP lúc chạy. Dựng: cd tests/vap-cel && go build -o <tệp> . ; dùng qua VAP_CEL=<tệp>.
// Vào (stdin): {"policy": <spec>, "cases": [{"object":..., "oldObject":..., "request":...}]}
// Ra: mỗi ca một dòng JSON {"match": bool, "failed": [message...], "errors": [...]}.
package main

import (
	"encoding/json"
	"fmt"
	"os"

	"github.com/google/cel-go/cel"
	"k8s.io/apimachinery/pkg/util/version"
	"k8s.io/apiserver/pkg/cel/environment"
)

type expr struct {
	Name       string `json:"name"`
	Expression string `json:"expression"`
	Message    string `json:"message"`
}

type input struct {
	Policy struct {
		MatchConditions []expr `json:"matchConditions"`
		Variables       []expr `json:"variables"`
		Validations     []expr `json:"validations"`
	} `json:"policy"`
	Cases []map[string]any `json:"cases"`
}

func main() {
	var in input
	if err := json.NewDecoder(os.Stdin).Decode(&in); err != nil {
		panic(err)
	}
	// Cùng môi trường CEL gốc của kube-apiserver 1.33 (thư viện mở rộng của Kubernetes), biến như VAP không kiểm kiểu.
	base := environment.MustBaseEnvSet(version.MajorMinor(1, 33), true)
	envs, err := base.Extend(environment.VersionedOptions{
		IntroducedVersion: version.MajorMinor(1, 0),
		EnvOptions: []cel.EnvOption{
			cel.Variable("object", cel.DynType), cel.Variable("oldObject", cel.DynType),
			cel.Variable("request", cel.DynType), cel.Variable("variables", cel.MapType(cel.StringType, cel.DynType)),
		},
	})
	if err != nil {
		panic(err)
	}
	env, err := envs.Env(environment.StoredExpressions)
	if err != nil {
		panic(err)
	}
	prog := func(s string) cel.Program {
		ast, iss := env.Compile(s)
		if iss.Err() != nil {
			fmt.Fprintln(os.Stderr, "biên dịch lỗi:", s, iss.Err())
			os.Exit(2)
		}
		p, err := env.Program(ast)
		if err != nil {
			panic(err)
		}
		return p
	}
	enc := json.NewEncoder(os.Stdout)
	for _, c := range in.Cases {
		vars := map[string]any{}
		act := map[string]any{"object": c["object"], "oldObject": c["oldObject"], "request": c["request"], "variables": vars}
		out := map[string]any{"match": true, "failed": []string{}, "errors": []string{}}
		for _, m := range in.Policy.MatchConditions {
			v, _, err := prog(m.Expression).Eval(act)
			if err != nil || v.Value() != true {
				out["match"] = false
			}
		}
		if out["match"] == true {
			for _, x := range in.Policy.Variables {
				v, _, err := prog(x.Expression).Eval(act)
				if err != nil {
					out["errors"] = append(out["errors"].([]string), x.Name+": "+err.Error())
					continue
				}
				vars[x.Name] = v
			}
			for _, x := range in.Policy.Validations {
				v, _, err := prog(x.Expression).Eval(act)
				if err != nil {
					out["errors"] = append(out["errors"].([]string), x.Message+": "+err.Error())
				} else if v.Value() != true {
					out["failed"] = append(out["failed"].([]string), x.Message)
				}
			}
		}
		enc.Encode(out)
	}
}
