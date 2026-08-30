import type { ReactNode, SelectHTMLAttributes } from 'react'
import { useId } from 'react'

import './field.css'

interface SelectFieldProps
  extends Omit<SelectHTMLAttributes<HTMLSelectElement>, 'id'> {
  label: string
  /** Shown under the field, in error ink. Its presence is what marks the field
   *  invalid — there is no separate flag to keep in step with it. */
  error?: string
  hint?: string
  children: ReactNode
}

/** The input's twin, wearing the same surface. */
export function SelectField({
  label,
  error,
  hint,
  children,
  ...rest
}: SelectFieldProps) {
  const id = useId()
  const messageId = `${id}-message`
  const invalid = error !== undefined

  return (
    <div className="field">
      <label className="field__label caption" htmlFor={id}>
        {label}
      </label>
      <select
        {...rest}
        id={id}
        className={`field__select${invalid ? ' field__select--invalid' : ''}`}
        aria-invalid={invalid || undefined}
        aria-describedby={
          error !== undefined || hint !== undefined ? messageId : undefined
        }
      >
        {children}
      </select>
      {error !== undefined && (
        <p className="field__error caption" id={messageId} role="alert">
          {error}
        </p>
      )}
      {error === undefined && hint !== undefined && (
        <p className="field__hint caption" id={messageId}>
          {hint}
        </p>
      )}
    </div>
  )
}
