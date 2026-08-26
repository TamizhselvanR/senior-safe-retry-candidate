import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App.jsx'

const failedTask = {
  taskId: 'task-17',
  workflowId: 'workflow-4',
  name: 'Transmit document',
  state: 'FAILED_RETRYABLE',
  version: 7,
  attemptCount: 2,
  lastError: 'Partner gateway timed out'
}

const completedTask = {
  taskId: 'task-18',
  workflowId: 'workflow-4',
  name: 'Validate payload',
  state: 'SUCCEEDED',
  version: 3,
  attemptCount: 1
}

function jsonResponse(body, status = 200) {
  return Promise.resolve(
    new Response(JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' }
    })
  )
}

describe('provided Retry Control Room shell', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('loads tasks with the supplied authentication context', async () => {
    fetch.mockReturnValueOnce(jsonResponse({ tasks: [failedTask, completedTask] }))

    render(<App authToken="tenant-alpha-token" />)

    expect(screen.getByRole('status')).toHaveTextContent('Loading tasks')
    expect(fetch).toHaveBeenCalledWith(
      '/api/tasks',
      expect.objectContaining({
        headers: expect.objectContaining({ Authorization: 'Bearer tenant-alpha-token' })
      })
    )
    expect(fetch.mock.calls[0][1].headers).not.toHaveProperty('X-Tenant-Id')
    expect(await screen.findByRole('button', { name: /Transmit document/i })).toBeVisible()
  })

  it('lets an operator select a task and inspect the supplied details', async () => {
    fetch.mockReturnValueOnce(jsonResponse({ tasks: [completedTask, failedTask] }))

    const user = userEvent.setup()
    render(<App authToken="tenant-alpha-token" />)

    await user.click(await screen.findByRole('button', { name: /Transmit document/i }))

    const detail = screen.getByRole('region', { name: 'Task details' })
    expect(within(detail).getByText('Partner gateway timed out')).toBeVisible()
    expect(within(detail).getByText('Version 7')).toBeVisible()
    expect(within(detail).getByRole('button', { name: 'Retry task' })).toBeEnabled()
  })

  it('shows an empty state after recovering from a list failure', async () => {
    fetch
      .mockReturnValueOnce(jsonResponse({ message: 'Database is unavailable' }, 503))
      .mockReturnValueOnce(jsonResponse({ tasks: [] }))

    const user = userEvent.setup()
    render(<App authToken="tenant-alpha-token" />)

    expect(await screen.findByRole('alert')).toHaveTextContent('Database is unavailable')
    await user.click(screen.getByRole('button', { name: 'Try again' }))

    expect(await screen.findByText('No tasks found')).toBeVisible()
    expect(fetch).toHaveBeenCalledTimes(2)
  })
})

describe('public retry contract — candidate implementation required', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('posts expectedVersion with a fresh Idempotency-Key and bearer token', async () => {
    fetch.mockReturnValueOnce(jsonResponse({ tasks: [failedTask] }))
    const user = userEvent.setup()
    render(<App authToken="tenant-alpha-token" />)

    const retryBtn = await screen.findByRole('button', { name: 'Retry task' })

    fetch.mockReturnValueOnce(jsonResponse({
      id: 'task-17',
      workflowId: 'workflow-4',
      title: 'Transmit document',
      status: 'RETRY_QUEUED',
      version: 8,
      attemptId: 'att-1',
      replayed: false
    }, 202))

    await user.click(retryBtn)

    expect(fetch).toHaveBeenCalledWith(
      '/api/workflows/workflow-4/tasks/task-17/retry',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          Authorization: 'Bearer tenant-alpha-token',
          'Idempotency-Key': expect.stringMatching(/^retry-key-/)
        }),
        body: JSON.stringify({ expectedVersion: 7 })
      })
    )
  })

  it('disables Retry while its request is pending and prevents duplicate clicks', async () => {
    fetch.mockReturnValueOnce(jsonResponse({ tasks: [failedTask] }))
    const user = userEvent.setup()
    render(<App authToken="tenant-alpha-token" />)

    const retryBtn = await screen.findByRole('button', { name: 'Retry task' })

    let resolveRetry
    fetch.mockReturnValueOnce(new Promise((resolve) => { resolveRetry = resolve }))

    await user.click(retryBtn)

    expect(retryBtn).toBeDisabled()
    expect(retryBtn).toHaveTextContent('Queuing retry…')

    resolveRetry(new Response(JSON.stringify({
      id: 'task-17',
      workflowId: 'workflow-4',
      title: 'Transmit document',
      status: 'RETRY_QUEUED',
      version: 8,
      attemptId: 'att-1',
      replayed: false
    }), { status: 202, headers: { 'Content-Type': 'application/json' } }))
  })

  it('updates the task in place after a 200 replay or 202 accepted response', async () => {
    fetch.mockReturnValueOnce(jsonResponse({ tasks: [failedTask] }))
    const user = userEvent.setup()
    render(<App authToken="tenant-alpha-token" />)

    const retryBtn = await screen.findByRole('button', { name: 'Retry task' })

    fetch.mockReturnValueOnce(jsonResponse({
      id: 'task-17',
      workflowId: 'workflow-4',
      title: 'Transmit document',
      status: 'RETRY_QUEUED',
      version: 8,
      attemptId: 'att-1',
      replayed: false
    }, 202))

    await user.click(retryBtn)

    const detail = screen.getByRole('region', { name: 'Task details' })
    expect(await within(detail).findByText('Version 8')).toBeVisible()
    expect(within(detail).getByText('Retry Queued')).toBeVisible()
  })

  it('presents a 409 conflict without hiding the Retry action', async () => {
    fetch.mockReturnValueOnce(jsonResponse({ tasks: [failedTask] }))
    const user = userEvent.setup()
    render(<App authToken="tenant-alpha-token" />)

    const retryBtn = await screen.findByRole('button', { name: 'Retry task' })

    fetch.mockReturnValueOnce(jsonResponse({
      status: 409,
      code: 'STALE_TASK_VERSION',
      message: 'The task version is stale'
    }, 409))

    await user.click(retryBtn)

    expect(await screen.findByRole('alert')).toHaveTextContent('The task version is stale')
    expect(screen.getByRole('button', { name: 'Retry task' })).toBeVisible()
  })

  it('does not let an older response overwrite a newer task version', async () => {
    fetch.mockReturnValueOnce(jsonResponse({ tasks: [failedTask] }))
    const user = userEvent.setup()
    render(<App authToken="tenant-alpha-token" />)

    const retryBtn = await screen.findByRole('button', { name: 'Retry task' })

    fetch.mockReturnValueOnce(jsonResponse({
      id: 'task-17',
      workflowId: 'workflow-4',
      title: 'Transmit document',
      status: 'RETRY_QUEUED',
      version: 8,
      attemptId: 'att-1',
      replayed: false
    }, 202))

    await user.click(retryBtn)

    const detail = screen.getByRole('region', { name: 'Task details' })
    expect(await within(detail).findByText('Version 8')).toBeVisible()

    // Simulate an older list fetch response with version 7 arriving later
    fetch.mockReturnValueOnce(jsonResponse({ tasks: [{ ...failedTask, version: 7 }] }))
    await user.click(screen.getByRole('button', { name: 'Refresh tasks' }))

    expect(within(detail).getByText('Version 8')).toBeVisible()
  })
})
