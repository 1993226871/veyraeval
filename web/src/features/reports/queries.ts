import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { ApiError, api, request } from "../../api/client";
import { useWorkspace } from "../auth/AuthProvider";

// Serialize guarded reads so a single page does not exhaust the server admission slots.
let reportReadTail = Promise.resolve();

export function queueReportRead<T>(
  signal: AbortSignal,
  read: () => Promise<T>,
) {
  const result = reportReadTail.then(() => {
    signal.throwIfAborted();
    return read();
  });
  reportReadTail = result.then(
    () => undefined,
    () => undefined,
  );
  return result;
}

export const reportReadRetry = {
  retry: (count: number, error: unknown) =>
    count < 2 && error instanceof ApiError && error.status === 429,
  retryDelay: (attempt: number) => 300 * (attempt + 1),
};

export function useComparisonTrials(left: string, right: string) {
  const { workspace_id } = useWorkspace();
  return useInfiniteQuery({
    queryKey: [workspace_id, "comparison-trials", left, right],
    initialPageParam: {} as { leftCursor?: string; rightCursor?: string },
    ...reportReadRetry,
    queryFn: ({ pageParam, signal }) =>
      queueReportRead(signal, () =>
        request(
          api.GET("/api/v1/reports/comparison/trials", {
            params: { query: { left, right, ...pageParam } },
            signal,
          }),
        ),
      ),
    getNextPageParam: ({ left, right }) =>
      left.next_cursor || right.next_cursor
        ? {
            leftCursor: left.next_cursor ?? undefined,
            rightCursor: right.next_cursor ?? undefined,
          }
        : undefined,
    refetchInterval: 10000,
  });
}

export function useReportTrials(id: string) {
  const { workspace_id } = useWorkspace();
  return useInfiniteQuery({
    queryKey: [workspace_id, "report-trials", id],
    initialPageParam: undefined as string | undefined,
    ...reportReadRetry,
    queryFn: ({ pageParam, signal }) =>
      queueReportRead(signal, () =>
        request(
          api.GET("/api/v1/reports/experiments/{id}/trials", {
            params: { path: { id }, query: { limit: 25, cursor: pageParam } },
            signal,
          }),
        ),
      ),
    getNextPageParam: (page) => page.next_cursor ?? undefined,
    refetchInterval: 10000,
  });
}

export function useReportDataset(id: string | undefined) {
  const { workspace_id } = useWorkspace();
  return useQuery({
    queryKey: [workspace_id, "report-dataset", id],
    enabled: !!id,
    queryFn: ({ signal }) =>
      request(
        api.GET("/api/v1/catalog/versions/{id}", {
          params: { path: { id: id! } },
          signal,
        }),
      ),
  });
}

export function useReportIdentity(id: string) {
  const { workspace_id } = useWorkspace();
  return useQuery({
    queryKey: [workspace_id, "experiment", id],
    queryFn: ({ signal }) =>
      request(
        api.GET("/api/v1/experiment-summaries/{id}", {
          params: { path: { id } },
          signal,
        }),
      ),
  });
}
