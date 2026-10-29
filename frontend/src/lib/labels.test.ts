import { describe, expect, it } from 'vitest'
import { ACCESS_LABEL, PROCESS_LABEL, SOURCE_LABEL, VISIBILITY_LABEL, formatBytes, processTone } from './labels'

describe('枚举文案', () => {
  it('覆盖知识库可见范围的全部取值', () => {
    for (const key of ['PUBLIC', 'ORG', 'PRIVATE', 'COURSE']) {
      expect(VISIBILITY_LABEL[key]).toBeTruthy()
    }
  })

  it('覆盖后端返回的文档来源（UPLOAD / EDITOR / IMPORT）', () => {
    expect(Object.keys(SOURCE_LABEL).sort()).toEqual(['EDITOR', 'IMPORT', 'UPLOAD'])
  })

  it('访问级别都有人话可读的文案', () => {
    for (const key of ['ADMIN', 'OWNER', 'MANAGER', 'EDITOR', 'VIEWER', 'PUBLIC', 'ORG', 'COURSE']) {
      expect(ACCESS_LABEL[key]).toBeTruthy()
    }
  })

  it('处理状态映射到对应色调', () => {
    expect(processTone('INDEXED')).toBe('success')
    expect(processTone('FAILED')).toBe('ochre')
    expect(processTone('PROCESSING')).toBe('teal')
    expect(processTone('PENDING')).toBe('muted')
    expect(processTone('UNKNOWN')).toBe('muted')
  })

  it('未知状态回落到原值而不是空白', () => {
    expect(PROCESS_LABEL['WHATEVER']).toBeUndefined()
  })
})

describe('formatBytes', () => {
  it('空值显示破折号', () => {
    expect(formatBytes(null)).toBe('—')
    expect(formatBytes(undefined)).toBe('—')
  })

  it('按量级切换单位', () => {
    expect(formatBytes(512)).toBe('512 B')
    expect(formatBytes(2048)).toBe('2.0 KB')
    expect(formatBytes(3 * 1024 * 1024)).toBe('3.0 MB')
  })
})
