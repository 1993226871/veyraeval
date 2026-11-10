"""Run the existing order example locally and push a completed evaluation."""
import argparse
import os
from getpass import getpass

from agent_eval import PushClient

from .agent import query_order
from .catalog import dataset


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="https://localhost:8443")
    parser.add_argument("--ca")
    parser.add_argument("--username", default=os.environ.get("VEYRAEVAL_USERNAME"))
    parser.add_argument("--project", default="订单助手评测")
    parser.add_argument("--suite")
    parser.add_argument("--variant", choices=("buggy", "correct"), default="correct")
    parser.add_argument("--namespace", default="订单回归")
    parser.add_argument("--save")
    parser.add_argument("--replay")
    args = parser.parse_args()
    if not args.username:
        parser.error("Use --username or set VEYRAEVAL_USERNAME")
    if not args.replay and not args.suite:
        parser.error("Choose a published rule version with --suite")
    with PushClient.login(args.url, args.username, getpass("平台密码: "), ca_cert=args.ca) as client:
        if args.replay:
            report = client.upload_file(args.replay)
        else:
            version = "v1" if args.variant == "buggy" else "v2"
            run = client.start_run(project=args.project, namespace=args.namespace, name=f"订单助手 {version}",
                                   agent_name="订单助手", agent_version=version,
                                   dataset_version="orders-v1", suite_version_id=args.suite)
            for task in dataset()["cases"]:
                order_id = task["input"]["order_id"]
                with run.case(id=order_id, instruction=task["instruction"], input=task["input"],
                              expected=task["expected"]) as case:
                    output = query_order(order_id)
                    if args.variant == "buggy" and "amount" in output:
                        output["amount"] /= 100
                    case.output = output
            if args.save:
                run.save(args.save)
            report = run.submit()
    print(f"{args.url.rstrip('/')}/workspace/reports/{report}")


if __name__ == "__main__":
    main()
