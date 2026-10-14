import { QueryClient } from '@tanstack/react-query';
import { ApiError } from './api/http';

export function makeQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 5_000,
        refetchOnWindowFocus: true,
        // 4xx will not fix itself; network blips and 5xx get two more tries
        retry: (count, e) => !(e instanceof ApiError && e.status < 500) && count < 2,
      },
    },
  });
}
