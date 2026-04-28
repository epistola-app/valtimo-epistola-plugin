export interface ExpressionFunctionInfo {
  name: string;
  description: string;
  overloads: OverloadInfo[];
}

export interface OverloadInfo {
  arguments: ArgumentInfo[];
  returnType: string;
}

export interface ArgumentInfo {
  name: string;
  type: string;
}
