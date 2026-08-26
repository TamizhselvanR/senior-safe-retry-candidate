import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchTasks, requestRetry } from './api.js'

describe('provided task-list API adapter', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('normalizes the published backend task shape for the supplied UI shell', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      new Response(JSON.stringify({
        tasks: [{
          id: 'task-alpha-retryable',
          workflowId: 'workflow-alpha',
          title: 'Submit invoice to gateway',
          status: 'FAILED_RETRYABLE',
          version: 0
        }]
      }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      })
    ))

    await expect(fetchTasks('tenant-alpha-token')).resolves.toEqual([{
      taskId: 'task-alpha-retryable',
      workflowId: 'workflow-alpha',
      name: 'Submit invoice to gateway',
      state: 'FAILED_RETRYABLE',
      version: 0,
      attemptCount: 0
    }])
  })

  it('posts retry request and normalizes the response payload', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      new Response(JSON.stringify({
        id: 'task-alpha-retryable',
        workflowId: 'workflow-alpha',
        title: 'Submit invoice to gateway',
        status: 'RETRY_QUEUED',
        version: 1,
        attemptId: 'att-123',
        replayed: false
      }), {
        status: 202,
        headers: { 'Content-Type': 'application/json' }
      })
    ))

    const task = {
      taskId: 'task-alpha-retryable',
      workflowId: 'workflow-alpha',
      name: 'Submit invoice to gateway',
      state: 'FAILED_RETRYABLE',
      version: 0
    }

    const res = await requestRetry({
      authToken: 'tenant-alpha-token',
      task,
      idempotencyKey: 'test-key-123'
    })

    expect(fetch).toHaveBeenCalledWith(
      '/api/workflows/workflow-alpha/tasks/task-alpha-retryable/retry',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          Authorization: 'Bearer tenant-alpha-token',
          'Idempotency-Key': 'test-key-123'
        }),
        body: JSON.stringify({ expectedVersion: 0 })
      })
    )

    expect(res).toEqual({
      taskId: 'task-alpha-retryable',
      workflowId: 'workflow-alpha',
      name: 'Submit invoice to gateway',
      state: 'RETRY_QUEUED',
      version: 1,
      attemptCount: 0
    })
  })
})
